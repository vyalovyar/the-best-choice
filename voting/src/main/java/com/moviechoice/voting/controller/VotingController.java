package com.moviechoice.voting.controller;


import com.moviechoice.voting.dto.MatchMessage;
import com.moviechoice.voting.dto.UpdateMovieIndexRequest;
import com.moviechoice.voting.dto.VoteMessage;
import com.moviechoice.voting.dto.VoteRequest;
import com.moviechoice.voting.dto.StartVotingRequest;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import com.moviechoice.voting.service.VotingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
        Vote vote = votingService.createVote(
                request.getSessionId(), request.getParticipantId(),
                request.getMovieId(), request.getDecision());

        VoteMessage message = new VoteMessage(
                vote.getId(), vote.getSessionId(), vote.getParticipantId(),
                vote.getMovie().getId(), vote.getMovie().getTitle(),
                vote.getDecision(), vote.getCreatedAt(), "NEW_VOTE");
        sendJson("/topic/session/" + request.getSessionId() + "/votes", message, "vote update");

        if (vote.getDecision() == VoteDecision.LIKE) {
            checkForMatch(vote);
        }
    }

    @MessageMapping("/start-voting")
    public void handleStartVoting(@Payload StartVotingRequest request) {
        log.info("Start voting requested for session={}, by={}", request.getSessionId(), request.getBy());
        try {
            simpMessagingTemplate.convertAndSend(
                    "/topic/session/" + request.getSessionId() + "/start",
                    "START"
            );
            log.info("Start voting message sent successfully");
        } catch (Exception e) {
            log.warn("Не удалось отправить WS start message: {}", e.getMessage(), e);
        }
    }

    @MessageMapping("/update-movie-index")
    public void handleUpdateMovieIndex(@Payload UpdateMovieIndexRequest request) {
        log.info("Обновление индекса фильма для сессии={}, индекс={}", request.getSessionId(), request.getMovieIndex());
        votingService.updateMovieIndexInSession(request.getSessionId(), request.getMovieIndex());
        // уведомляем всех участников сессии об обновлённом индексе, чтобы клиенты синхронизировались
        try {
            sendJson("/topic/session/" + request.getSessionId() + "/index", request, "movie index update");
        } catch (Exception e) {
            log.warn("Не удалось отправить WS-уведомление об обновлении индекса: {}", e.getMessage());
        }
    }

    private void checkForMatch(Vote vote) {
        Movie movie = vote.getMovie();
        long likes = votingService.countLikes(vote.getSessionId(), movie.getId());
        if (likes < 2) {
            return;
        }
        MatchMessage match = new MatchMessage(
                vote.getSessionId(), movie.getId(), movie.getTitle(),
                movie.getPosterPath(), "У вас совпадение!");
        sendJson("/topic/session/" + vote.getSessionId() + "/match", match, "match notification");
        log.info("Совпадение: сессия={}, фильм={}", vote.getSessionId(), movie.getId());
    }

    //Метод для отправки JSON сообщений через WebSocket
    private void sendJson(String destination, Object payload, String payloadType) {
        try {
            simpMessagingTemplate.convertAndSend(destination, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize " + payloadType, e);
        }
    }
}
