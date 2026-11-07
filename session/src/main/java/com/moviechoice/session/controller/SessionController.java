package com.moviechoice.session.controller;


import java.util.HashMap;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.service.SessionService;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/sessions")
//пока побудет для всех, потому что домены разные
@CrossOrigin(origins = "*")
@Slf4j
public class SessionController {
    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createSession(){
        //создаем сессию
        Session session = sessionService.createSession();
        //собираем response быстрее
        return ResponseEntity.ok(buildSessionResponse(session, List.of()));
    }

    @GetMapping("/code")
    public ResponseEntity<Map<String, Object>> getSessionCode(@RequestParam String code) {
        return sessionService.findByCode(code).
                map(session -> {
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
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{sessionId}/participants")
    public ResponseEntity<Map<String, Object>> getParticipants(@PathVariable String sessionId) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            return sessionService.getSessionById(uuid)
                    .map(session -> {
                        List<Participant> participants = sessionService.getParticipants(uuid);
                        log.info("Participants requested for session {}: {} participant(s)", uuid, participants.size());

                        Map<String, Object> res = new HashMap<>();
                        res.put("sessionId", session.getId().toString());
                        res.put("status", session.getStatus().toString());
                        res.put("votingStarted", Boolean.TRUE.equals(session.getVotingStarted()));
                        res.put("currentMovieIndex", session.getCurrentMovieIndex());
                        res.put("participants", buildParticipantsResponse(participants));
                        return ResponseEntity.ok(res);
                    }).orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{sessionId}/join")
    public ResponseEntity<Map<String, Object>> joinSession(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        try {
            UUID uuid = UUID.fromString(sessionId);
            String participantName = body.get("name");
            
            if (participantName == null || participantName.isBlank()) {
                Map<String, Object> err = new HashMap<>();
                err.put("error", "Укажите никнейм");
                return ResponseEntity.badRequest().body(err);
            }
            if (sessionService.isNameTaken(uuid, participantName)) {
                Map<String, Object> res = new HashMap<>();
                res.put("error", "Этот никнейм уже занят в сессии");
                res.put("code", "NAME_TAKEN");
                return ResponseEntity.status(HttpStatus.CONFLICT).body(res);
            }

            Participant participant = sessionService.addParticipant(uuid, participantName);
            if (participant == null) {
                return ResponseEntity.notFound().build();
            }

            log.info("Participant joined session {}: id={}, name={}", uuid, participant.getId(), participant.getName());
            
            Map<String, Object> res = new HashMap<>();
            res.put("participantId", participant.getId().toString());
            res.put("name", participant.getName());
            res.put("joinedAt", participant.getJoinedAt());
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
            if (sessionService.getSessionById(uuid).isEmpty()) {
                return ResponseEntity.notFound().build();
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

    @PostMapping("/{sessionId}/update-index")
    public ResponseEntity<Map<String, String>> updateSessionMovieIndex(
            @PathVariable String sessionId, 
            @RequestBody Map<String, Integer> body) {
        try {
            UUID uuid = UUID.fromString(sessionId);
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
    public ResponseEntity<Map<String, Object>> startSession(@PathVariable String sessionId) {
        try {
            UUID uuid = UUID.fromString(sessionId);

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
    public ResponseEntity<Map<String, Object>> finishSession(@PathVariable String sessionId) {
        try {
            UUID uuid = UUID.fromString(sessionId);

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

    private Map<String, Object> buildSessionResponse(Session session, List<Participant> participants) {
        Map<String, Object> res = new HashMap<>();
        res.put("sessionId", session.getId().toString());
        res.put("code", session.getCode().toString());
        res.put("status", session.getStatus().toString());
        res.put("votingStarted", Boolean.TRUE.equals(session.getVotingStarted()));
        res.put("currentMovieIndex", session.getCurrentMovieIndex());
        res.put("participants", buildParticipantsResponse(participants));
        return res;
    }

    private List<Map<String, String>> buildParticipantsResponse(List<Participant> participants) {
        return participants.stream()
                .map(p -> {
                    Map<String, String> pMap = new HashMap<>();
                    pMap.put("id", p.getId().toString());
                    pMap.put("name", p.getName());
                    pMap.put("joinedAt", formatDateTime(p.getJoinedAt()));
                    return pMap;
                })
                .collect(Collectors.toList());
    }

    private String formatDateTime(ZonedDateTime dateTime) {
        return dateTime == null ? null : dateTime.toOffsetDateTime().toString();
    }
}
