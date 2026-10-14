package com.moviechoice.voting.controller;


import com.moviechoice.voting.dto.*;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
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
import org.springframework.web.bind.annotation.*;

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
    public List<Movie> getMovies() {
        return votingService.getMoviesForVote();
    }

    @GetMapping("/sessions/{sessionId}/votes")
    public List<Vote> getVotes(@PathVariable UUID sessionId) {
        return votingService.getAllVotesInSession(sessionId);
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
                            movie.getPosterPath(), "У вас совпадение!"), "match notification");
                    log.info("Совпадение: сессия={}, фильм={}", vote.getSessionId(), movie.getId());
                }
                case NEXT -> sendJson(base + "/index",
                        new UpdateMovieIndexRequest(vote.getSessionId(), outcome.nextMovieIndex()),
                        "movie index update");
                case WAITING -> { }
            }
        }catch (Exception e) {
            log.error("Ошибка голосования", e);
            sendJson("/topic/session/" + request.getSessionId() + "/errors",
                    Map.of("participantId", request.getParticipantId(),
                            "message", e.getMessage() == null ? "Ошибка голосования" : e.getMessage()),
                    "error");
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

    private void sendJson(String destination, Object payload, String payloadType) {
        try {
            simpMessagingTemplate.convertAndSend(destination, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + payloadType, e);
        }
    }
}