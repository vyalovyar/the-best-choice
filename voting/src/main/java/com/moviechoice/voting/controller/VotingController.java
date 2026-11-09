package com.moviechoice.voting.controller;


import com.moviechoice.voting.dto.*;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.service.VotingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/voting")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class VotingController {
    private final VotingService votingService;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/movies")
    public List<MovieResponse> getMovies() {
        return votingService.getMoviesForVote();
    }

    @GetMapping("/movies/{index}")
    public ResponseEntity<MovieResponse> getMovieAt(@PathVariable int index) {
        return votingService.getMovieAtIndex(index)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/sessions/{sessionId}/votes")
    public List<Vote> getVotes(@PathVariable UUID sessionId) {
        return votingService.getAllVotesInSession(sessionId);
    }

    @PostMapping("/sessions/{sessionId}/participants-changed")
    public void participantsChanged(@PathVariable UUID sessionId) {
        simpMessagingTemplate.convertAndSend("/topic/session/" + sessionId + "/participants", "CHANGED");
        log.info("Разослано уведомление об изменении состава сессии {}", sessionId);
    }

    @PostMapping("/sessions/{sessionId}/participant-removed")
    public void participantRemoved(@PathVariable UUID sessionId, @RequestBody(required = false) Map<String, String> body) {
        Map<String, String> payload = new HashMap<>();
        payload.put("participantId", body == null ? "" : body.getOrDefault("participantId", ""));
        payload.put("participantName", body == null ? "" : body.getOrDefault("participantName", ""));
        payload.put("reason", body == null ? "LEFT" : body.getOrDefault("reason", "LEFT"));
        simpMessagingTemplate.convertAndSend("/topic/session/" + sessionId + "/left", payload);
        log.info("Участник {} покинул сессию {} (причина: {})",
                payload.get("participantId"), sessionId, payload.get("reason"));
    }

    @MessageMapping("/vote")
    public void handlerVote(@Payload VoteRequest request) {
        try {
            VoteOutcome outcome = votingService.castVote(
                    request.getSessionId(), request.getParticipantId(),
                    request.getMovieId(), request.getDecision());
            Vote vote = outcome.vote();
            String base = "/topic/session/" + request.getSessionId();

            VoteMessage message = new VoteMessage(
                    vote.getId(), vote.getSessionId(), vote.getParticipantId(),
                    vote.getMovie().getId(), vote.getMovie().getTitle(),
                    vote.getDecision(), vote.getCreatedAt(), "NEW_VOTE",
                    outcome.voted(), outcome.total());
            sendJson(base + "/votes", message, "vote update");

            switch (outcome.status()) {
                case MATCH -> {
                    Movie movie = vote.getMovie();
                    sendJson(base + "/match", new MatchMessage(
                            vote.getSessionId(), movie.getId(), movie.getTitle(),
                            movie.getPosterPath(), movie.getGenre(), movie.getVoteAvg(),
                            "У вас совпадение!"), "match notification");
                    log.info("Совпадение: сессия={}, фильм={}", vote.getSessionId(), movie.getId());
                }
                case NEXT -> sendJson(base + "/index",
                        new UpdateMovieIndexRequest(vote.getSessionId(), outcome.nextMovieIndex()),
                        "movie index update");
                case WAITING -> { }
            }
        } catch (VotingService.StaleVoteException e) {
            log.info("Устаревший голос: сессия={}, участник={}", request.getSessionId(), request.getParticipantId());
            sendParticipantError(request.getSessionId(), request.getParticipantId(), e.getMessage());
        } catch (Exception e) {
            log.error("Ошибка голосования", e);
            sendParticipantError(request.getSessionId(), request.getParticipantId(),
                    e.getMessage() == null ? "Ошибка голосования" : e.getMessage());
        }
    }

    @MessageMapping("/start-voting")
    public void handleStartVoting(@Payload StartVotingRequest request) {
        log.info("Start voting requested for session={}, by={}", request.getSessionId(), request.getBy());
        simpMessagingTemplate.convertAndSend("/topic/session/" + request.getSessionId() + "/start", "START");
    }

    @MessageExceptionHandler
    @SendToUser(value = "/queue/errors", broadcast = false)
    public Map<String, String> handleError(Exception e) {
        log.warn("Ошибка обработки голоса: {}", e.getMessage());
        return Map.of("message", e.getMessage() == null ? "Ошибка голосования" : e.getMessage());
    }

    private void sendParticipantError(UUID sessionId, String participantId, String message) {
        sendJson("/topic/session/" + sessionId + "/errors",
                Map.of("participantId", participantId == null ? "" : participantId,
                        "message", message == null ? "Ошибка голосования" : message),
                "error");
    }

    private void sendJson(String destination, Object payload, String payloadType) {
        try {
            simpMessagingTemplate.convertAndSend(destination, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + payloadType, e);
        }
    }
}
