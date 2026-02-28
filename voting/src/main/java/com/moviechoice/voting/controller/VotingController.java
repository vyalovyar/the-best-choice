package com.moviechoice.voting.controller;


import com.moviechoice.voting.dto.MatchMessage;
import com.moviechoice.voting.dto.VoteMessage;
import com.moviechoice.voting.dto.VoteRequest;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import com.moviechoice.voting.service.VotingService;
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
                request.getSessionId(),
                request.getParticipantId(),
                request.getMovieId(),
                request.getDecision()
        );

        VoteMessage message = new VoteMessage(
                vote.getId(),
                vote.getSessionId(),
                vote.getParticipantId(),
                vote.getMovie().getId(),
                vote.getMovie().getTitle(),
                vote.getDecision(),
                vote.getCreatedAt(),
                "NEW_VOTE"
        );
        
        //отправляем сообщение о голосе через WebSocket всем кто в сессии
        simpMessagingTemplate.convertAndSend(
                "/topic/session/" + request.getSessionId() + "/votes",
                message
        );
        
        //проверяем совпадения голосов
        chekForMath(request.getSessionId(), request.getMovieId());
    }

    private void chekForMath(UUID sessionId, long movieId) {
        List<Vote> votes = votingService.getAllVotesInSession(sessionId);
        
        //достаём только голоса за текущий фильм
        List<Vote> currentMovieVotes = votes.stream()
                .filter(v -> v.getMovie().getId().equals(movieId))
                .toList();

        // Считаем да по текущему фильму
        long likeCount = currentMovieVotes.stream()
                .filter(v -> v.getDecision() == VoteDecision.LIKE)
                .count();

        // Если оба проголосовали Да то отправляем уведомление о совпадении
        if (likeCount >= 2) {
            Movie movie = currentMovieVotes.stream()
                    .findFirst()
                    .map(Vote::getMovie)
                    .orElse(null);
            
            if (movie != null) {
                MatchMessage matchMessage = new MatchMessage(
                        sessionId,
                        movieId,
                        movie.getTitle(),
                        movie.getPosterPath(),
                        "У вас совпадение!"
                );

                simpMessagingTemplate.convertAndSend(
                        "/topic/session/" + sessionId + "/match",
                        matchMessage
                );
                
                log.info("Отправлено уведомление о совпадении для сессии={}, фильма={}", sessionId, movieId);
            }
        }
    }
}
