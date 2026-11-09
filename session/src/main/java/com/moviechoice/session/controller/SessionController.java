package com.moviechoice.session.controller;

import java.util.HashMap;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.security.RateLimiter;
import com.moviechoice.session.service.SessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "*")
@Slf4j
public class SessionController {
    private final SessionService sessionService;
    private final RateLimiter rateLimiter;

    @Value("${session.internal-token:change-me-internal}")
    private String internalSecret = "change-me-internal";

    public SessionController(SessionService sessionService, RateLimiter rateLimiter) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(HttpServletRequest request) {
        if (!rateLimiter.tryAcquire("create:" + clientKey(request))) {
            return tooManyRequests();
        }
        Session session = sessionService.createSession();
        Map<String, Object> response = buildSessionResponse(session, List.of());
        response.put("hostToken", session.getHostToken());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/code")
    public ResponseEntity<Map<String, Object>> getSessionCode(@RequestParam String code,
                                                              HttpServletRequest request) {
        String normalized = code == null ? "" : code.trim().toUpperCase();
        if (normalized.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!rateLimiter.tryAcquire("lookup:" + clientKey(request))) {
            return tooManyRequests();
        }
        return sessionService.findByCode(normalized)
                .map(session -> {
                    List<Participant> participants = sessionService.getParticipants(session.getId());
                    return ResponseEntity.ok(buildSessionResponse(session, participants));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSessionById(@PathVariable String sessionId) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            return sessionService.getSessionById(uuid)
                    .map(session -> {
                        List<Participant> participants = sessionService.getParticipants(uuid);
                        log.info("Session {} fetched with {} participant(s)", uuid, participants.size());
                        return ResponseEntity.ok(buildSessionResponse(session, participants));
                    }).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{sessionId}/participants")
    public ResponseEntity<Map<String, Object>> getParticipants(@PathVariable String sessionId,
                                                               @RequestParam(required = false) String participantId) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            return sessionService.getSessionById(uuid)
                    .map(session -> {
                        if (participantId != null) {
                            sessionService.heartbeat(uuid, parseUuid(participantId));
                        }
                        List<Participant> participants = sessionService.getParticipants(uuid);
                        Map<String, Object> res = new HashMap<>();
                        res.put("sessionId", session.getId().toString());
                        res.put("status", session.getStatus().toString());
                        res.put("votingStarted", Boolean.TRUE.equals(session.getVotingStarted()));
                        res.put("currentMovieIndex", session.getCurrentMovieIndex());
                        res.put("participants", buildParticipantsResponse(participants));
                        return ResponseEntity.ok(res);
                    }).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{sessionId}/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(@PathVariable String sessionId,
                                                         @RequestBody(required = false) Map<String, String> body) {
        try {
            UUID sessionIdUuid = UUID.fromString(sessionId);
            String participantId = body == null ? null : body.get("participantId");
            if (participantId == null) {
                return ResponseEntity.badRequest().build();
            }
            if (!sessionService.heartbeat(sessionIdUuid, parseUuid(participantId))) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("alive", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{sessionId}/join")
    public ResponseEntity<Map<String, Object>> joinSession(@PathVariable String sessionId,
                                                           @RequestBody Map<String, String> body,
                                                           HttpServletRequest request) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            if (!rateLimiter.tryAcquire("join:" + clientKey(request))) {
                return tooManyRequests();
            }

            String participantName = body.get("name");
            String token = body.get("token");
            /* Право хоста подтверждается только токеном, выданным при создании
               сессии. Флаг из тела запроса подделать проще простого. */
            boolean host = sessionService.isHostToken(uuid, body.get("hostToken"));

            if (participantName == null || participantName.isBlank()) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Укажите никнейм");
                return ResponseEntity.badRequest().body(err);
            }

            Session session = sessionService.getSessionById(uuid).orElse(null);
            if (session == null) {
                return ResponseEntity.notFound().build();
            }

            /* Возврат в уже начатое голосование: пускаем только своего
               участника, предъявившего токен, полученный при входе. */
            if (isVotingLocked(session)) {
                Participant existing = sessionService.findByToken(uuid, token).orElse(null);
                if (existing == null) {
                    Map<String, Object> err = new HashMap<>();
                    err.put("error", "Голосование уже началось — войти нельзя");
                    err.put("code", "VOTING_STARTED");
                    return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
                }
                sessionService.heartbeat(uuid, existing.getId());
                log.info("Participant rejoined voting session {}: id={}", uuid, existing.getId());
                return ResponseEntity.ok(buildParticipantResponse(existing, session));
            }

            if (sessionService.isNameTaken(uuid, participantName)) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Этот никнейм уже занят в сессии");
                res.put("code", "NAME_TAKEN");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(res);
            }

            Participant participant = sessionService.addParticipant(uuid, participantName, host);
            if (participant == null) {
                return ResponseEntity.notFound().build();
            }

            log.info("Participant joined session {}: id={}, name={}", uuid, participant.getId(), participant.getName());
            return ResponseEntity.ok(buildParticipantResponse(participant, session));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{sessionId}/participants/{participantId}")
    public ResponseEntity<Map<String, Object>> leaveSession(
            @PathVariable String sessionId, @PathVariable String participantId) {
        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            UUID participantUuid = UUID.fromString(participantId);

            Participant participant = sessionService.findByParticipantId(participantUuid).orElse(null);
            if (participant == null) {
                return ResponseEntity.notFound().build();
            }
            if (participant.getSession() == null || !sessionUuid.equals(participant.getSession().getId())) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Участник не относится к этой сессии");
                return ResponseEntity.badRequest().body(res);
            }

            sessionService.removeParticipant(participantUuid);

            List<Participant> remaining = sessionService.getParticipants(sessionUuid);
            Map<String, Object> res = new HashMap<>();
            res.put("sessionId", sessionUuid.toString());
            res.put("participantId", participantId);
            res.put("participants", buildParticipantsResponse(remaining));
            log.info("Participant {} left session {}, remaining: {}",
                    participantId, sessionUuid, remaining.size());
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{sessionId}/participants/{participantId}/host")
    public ResponseEntity<Map<String, Object>> kickParticipant(
            @PathVariable String sessionId,
            @PathVariable String participantId,
            @RequestParam(required = false) String requesterId,
            @RequestParam(required = false) String hostToken) {
        try {
            UUID sessionUuid = UUID.fromString(sessionId);
            UUID participantUuid = UUID.fromString(participantId);
            UUID requesterUuid = parseUuid(requesterId);

            if (!hasHostRights(sessionUuid, requesterUuid, hostToken)) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Исключать участников может только хост");
                err.put("code", "NOT_HOST");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
            }
            if (participantUuid.equals(requesterUuid)) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Нельзя исключить самого себя");
                err.put("code", "SELF_KICK");
                return ResponseEntity.badRequest().body(err);
            }

            Participant target = sessionService.findByParticipantId(participantUuid).orElse(null);
            if (target == null || target.getSession() == null || !sessionUuid.equals(target.getSession().getId())) {
                return ResponseEntity.notFound().build();
            }

            sessionService.removeParticipant(participantUuid, "KICKED");

            List<Participant> remaining = sessionService.getParticipants(sessionUuid);
            Map<String, Object> res = new HashMap<>();
            res.put("sessionId", sessionUuid.toString());
            res.put("participantId", participantId);
            res.put("participants", buildParticipantsResponse(remaining));
            log.info("Participant {} kicked from session {} by {}", participantId, sessionUuid, requesterUuid);
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{sessionId}/name-available")
    public ResponseEntity<Map<String, Object>> isNameAvailable(
            @PathVariable String sessionId, @RequestParam String name) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            Session session = sessionService.getSessionById(uuid).orElse(null);
            if (session == null) {
                return ResponseEntity.notFound().build();
            }
            if (isVotingLocked(session)) {
                Map<String, Object> res = new HashMap<>();
                res.put("available", false);
                res.put("code", "VOTING_STARTED");
                res.put("error", "Голосование уже началось — войти нельзя");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(res);
            }

            String normalizedName = name == null ? "" : name.trim().replaceAll("\\s+", " ");
            Map<String, Object> res = new HashMap<>();

            if (normalizedName.isEmpty()) {
                res.put("available", false);
                res.put("error", "Никнейм не может быть пустым");
                return ResponseEntity.badRequest().body(res);
            }
            if (normalizedName.length() > 20) {
                res.put("available", false);
                res.put("error", "Никнейм не длиннее 20 символов");
                return ResponseEntity.badRequest().body(res);
            }

            boolean taken = sessionService.isNameTaken(uuid, normalizedName);
            res.put("available", !taken);
            res.put("name", normalizedName);
            if (taken) {
                res.put("error", "Этот никнейм уже занят в сессии");
            }
            return ResponseEntity.ok(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{sessionId}/update-index")
    public ResponseEntity<Map<String, String>> updateSessionMovieIndex(
            @PathVariable String sessionId,
            @RequestBody Map<String, Integer> body,
            @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            if (!isInternalCall(internalToken)) {
                log.warn("Отклонён внешний вызов update-index для сессии {}", sessionId);
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            Integer movieIndex = body.get("currentMovieIndex");

            return sessionService.updateMovieIndex(uuid, movieIndex)
                    .map(updatedSession -> {
                        Map<String, String> res = new HashMap<>();
                        res.put("sessionId", updatedSession.getId().toString());
                        res.put("currentMovieIndex", updatedSession.getCurrentMovieIndex().toString());
                        return ResponseEntity.ok(res);
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            Map<String, String> res = new HashMap<>();
            res.put("error", "Session was updated concurrently. Please retry.");
            return ResponseEntity.status(409).body(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<Map<String, Object>> startSession(@PathVariable String sessionId,
                                                            @RequestParam(required = false) String requesterId,
                                                            @RequestParam(required = false) String hostToken) {
        try {
            UUID uuid = UUID.fromString(sessionId);

            if (!hasHostRights(uuid, parseUuid(requesterId), hostToken)) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Начать голосование может только хост");
                res.put("code", "NOT_HOST");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(res);
            }

            List<Participant> participants = sessionService.getParticipants(uuid);
            if (participants.isEmpty() && sessionService.getSessionById(uuid).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (participants.size() < 2) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Для старта нужно минимум 2 участника");
                res.put("participants", buildParticipantsResponse(participants));
                return ResponseEntity.badRequest().body(res);
            }

            return sessionService.startSession(uuid)
                    .map(updatedSession -> ResponseEntity.ok(buildSessionResponse(updatedSession, participants)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("error", "Session was updated concurrently. Please retry.");
            return ResponseEntity.status(409).body(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{sessionId}/finish")
    public ResponseEntity<Map<String, Object>> finishSession(@PathVariable String sessionId,
                                                             @RequestParam(required = false) String requesterId,
                                                             @RequestParam(required = false) String hostToken,
                                                             @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        try {
            UUID uuid = UUID.fromString(sessionId);

            if (!isInternalCall(internalToken) && !hasHostRights(uuid, parseUuid(requesterId), hostToken)) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Завершить голосование может только хост");
                res.put("code", "NOT_HOST");
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(res);
            }

            return sessionService.finishSession(uuid)
                    .map(updatedSession -> {
                        List<Participant> participants = sessionService.getParticipants(uuid);
                        return ResponseEntity.ok(buildSessionResponse(updatedSession, participants));
                    })
                    .orElse(ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            Map<String, Object> res = new HashMap<>();
            res.put("error", "Session was updated concurrently. Please retry.");
            return ResponseEntity.status(409).body(res);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    private boolean isInternalCall(String internalToken) {
        return internalToken != null && internalSecret != null
                && !internalSecret.isBlank() && internalToken.equals(internalSecret);
    }

    private boolean hasHostRights(UUID sessionId, UUID requesterId, String hostToken) {
        return sessionService.isHost(sessionId, requesterId)
                || sessionService.isHostToken(sessionId, hostToken);
    }

    private boolean isVotingLocked(Session session) {
        return Boolean.TRUE.equals(session.getVotingStarted())
                || session.getStatus() == SessionStatus.VOTING
                || session.getStatus() == SessionStatus.FINISHED;
    }

    private UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String clientKey(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private ResponseEntity<Map<String, Object>> tooManyRequests() {
        Map<String, Object> res = new HashMap<>();
        res.put("error", "Слишком много попыток — подождите минуту");
        res.put("code", "RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(res);
    }

    private Map<String, Object> buildSessionResponse(Session session, List<Participant> participants) {
        Map<String, Object> res = new HashMap<>();
        res.put("sessionId", session.getId().toString());
        res.put("code", session.getCode());
        res.put("status", session.getStatus().toString());
        res.put("votingStarted", Boolean.TRUE.equals(session.getVotingStarted()));
        res.put("currentMovieIndex", session.getCurrentMovieIndex());
        res.put("participants", buildParticipantsResponse(participants));
        return res;
    }

    private Map<String, Object> buildParticipantResponse(Participant participant, Session session) {
        Map<String, Object> res = new HashMap<>();
        res.put("participantId", participant.getId().toString());
        res.put("name", participant.getName());
        res.put("joinedAt", formatDateTime(participant.getJoinedAt()));
        res.put("host", participant.isHost());
        res.put("token", participant.getToken());
        res.put("sessionId", session.getId().toString());
        res.put("code", session.getCode());
        res.put("currentMovieIndex", session.getCurrentMovieIndex());
        res.put("votingStarted", Boolean.TRUE.equals(session.getVotingStarted()));
        return res;
    }

    private List<Map<String, Object>> buildParticipantsResponse(List<Participant> participants) {
        return participants.stream()
                .map(p -> {
                    Map<String, Object> pMap = new HashMap<>();
                    pMap.put("id", p.getId().toString());
                    pMap.put("name", p.getName());
                    pMap.put("joinedAt", formatDateTime(p.getJoinedAt()));
                    pMap.put("host", p.isHost());
                    return pMap;
                })
                .collect(Collectors.toList());
    }

    private String formatDateTime(ZonedDateTime dateTime) {
        return dateTime == null ? null : dateTime.toOffsetDateTime().toString();
    }
}
