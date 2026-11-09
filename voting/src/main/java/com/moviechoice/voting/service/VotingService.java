package com.moviechoice.voting.service;

import com.moviechoice.voting.dto.MovieResponse;
import com.moviechoice.voting.dto.VoteOutcome;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import com.moviechoice.voting.repository.MovieRepository;
import com.moviechoice.voting.repository.VoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class VotingService {

    private final MovieRepository movieRepository;
    private final VoteRepository voteRepository;
    private final RestTemplate restTemplate;
    private final MovieCacheService movieCacheService;
    private final CatalogService catalogService;

    @Value("${session.service.url:http://localhost:8081}")
    private String sessionServiceUrl;

    @Value("${session.internal-token:change-me-internal}")
    private String internalToken;

    private static final String SESSION_PARTICIPANTS_PATH = "/api/sessions/%s/participants";
    private static final String SESSION_UPDATE_INDEX_PATH = "/api/sessions/%s/update-index";
    private static final String SESSION_FINISH_PATH = "/api/sessions/%s/finish";

    private final Object refreshLock = new Object();
    private final ConcurrentHashMap<UUID, Object> sessionLocks = new ConcurrentHashMap<>();

    public List<Movie> loadMovies() {
        if (movieRepository.count() == 0) {
            synchronized (refreshLock) {
                if (movieRepository.count() == 0) {
                    log.info("Каталог пуст, загружаем фильмы из TMDB");
                    movieCacheService.refreshMovies();
                    movieCacheService.assignMissingPositions();
                }
            }
        }
        return movieRepository.findAll(Sort.by("catalogPosition", "id"));
    }

    public List<MovieResponse> getMoviesForVote() {
        return loadMovies().stream().map(MovieResponse::from).toList();
    }

    public Optional<MovieResponse> getMovieAtIndex(int index) {
        if (index < 0) {
            return Optional.empty();
        }
        /* Индекс за пределами загруженного каталога: подтягиваем следующую
           пачку страниц TMDB, иначе игра упирается в первые 20 фильмов. */
        catalogService.ensureHasMovieAt(index);

        List<Long> ids = movieRepository.findAllIdsOrdered();
        Long movieId = movieIdAt(ids, index);
        if (movieId == null) {
            return Optional.empty();
        }
        return movieRepository.findById(movieId).map(MovieResponse::from);
    }

    public void assignMissingPositions() {
        movieCacheService.assignMissingPositions();
    }

    @Transactional
    public void updateMoviesFromTmdb() {
        movieCacheService.refreshMovies();
        movieCacheService.assignMissingPositions();
    }

    @Transactional
    public Vote createVote(UUID sessionId, String participantId, Long movieId, VoteDecision decision) {
        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new RuntimeException("Фильм не найден: " + movieId));

        Vote vote = voteRepository
                .findBySessionIdAndParticipantIdAndMovie_Id(sessionId, participantId, movieId)
                .orElseGet(() -> Vote.builder()
                        .sessionId(sessionId)
                        .participantId(participantId)
                        .build());
        vote.setMovie(movie);
        vote.setDecision(decision);
        vote.setCreatedAt(ZonedDateTime.now());

        log.info("Голос сохранён: участник={}, фильм={}, решение={}", participantId, movieId, decision);
        return voteRepository.save(vote);
    }

    public VoteOutcome castVote(UUID sessionId, String participantId, Long movieId, VoteDecision decision) {
        synchronized (sessionLocks.computeIfAbsent(sessionId, id -> new Object())) {
            SessionSnapshot snapshot = getSessionSnapshot(sessionId);
            List<String> participants = snapshot.participants();

            if (participants.size() < 2) {
                throw new IllegalStateException("Для голосования нужно минимум 2 участника");
            }
            if (!participants.contains(participantId)) {
                throw new IllegalStateException("Участник не найден в сессии");
            }

            List<Long> ids = movieRepository.findAllIdsOrdered();
            Long currentMovieId = movieIdAt(ids, snapshot.currentMovieIndex());
            if (currentMovieId != null && !currentMovieId.equals(movieId)) {
                String title = movieRepository.findById(currentMovieId).map(Movie::getTitle).orElse(null);
                throw new StaleVoteException(title == null
                        ? "Голос не принят: фильм уже сменился"
                        : String.format("Голос не принят: фильм уже сменился (сейчас «%s»)", title));
            }

            Vote vote = createVote(sessionId, participantId, movieId, decision);
            int total = participants.size();
            long voted = voteRepository.countParticipantsVoted(sessionId, movieId);

            if (voted < total) {
                return new VoteOutcome(vote, voted, total, VoteOutcome.Status.WAITING, null);
            }

            long likes = voteRepository.countDistinctParticipants(sessionId, movieId, VoteDecision.LIKE);
            voteRepository.deleteBySessionIdAndMovie_Id(sessionId, movieId);

            if (likes >= total) {
                finishSession(sessionId);
                return new VoteOutcome(vote, voted, total, VoteOutcome.Status.MATCH, null);
            }

            int next = nextMovieIndex(snapshot.currentMovieIndex());
            if (!updateMovieIndexInSession(sessionId, next)) {
                throw new IllegalStateException("Не удалось переключить фильм, попробуйте ещё раз");
            }
            return new VoteOutcome(vote, voted, total, VoteOutcome.Status.NEXT, next);
        }
    }

    private HttpHeaders internalHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Token", internalToken);
        return headers;
    }

    private static Long movieIdAt(List<Long> ids, int index) {
        if (ids == null || ids.isEmpty() || index < 0 || index >= ids.size()) {
            return null;
        }
        return ids.get(index);
    }

    private int nextMovieIndex(int currentIndex) {
        int next = currentIndex + 1;
        if (catalogService.ensureHasMovieAt(next)) {
            return next;
        }

        log.info("Каталог закончился на индексе {}, начинаем новый круг", currentIndex);
        catalogService.resetPaging();
        return 0;
    }

    @SuppressWarnings("unchecked")
    public SessionSnapshot getSessionSnapshot(UUID sessionId) {
        String url = sessionServiceUrl + String.format(SESSION_PARTICIPANTS_PATH, sessionId);
        Map<String, Object> body = restTemplate.getForObject(url, Map.class);
        if (body == null) {
            throw new IllegalStateException("Не удалось получить состояние сессии");
        }

        List<String> participants = List.of();
        if (body.get("participants") instanceof List<?> raw) {
            participants = ((List<Map<String, Object>>) raw).stream()
                    .map(p -> String.valueOf(p.get("id")))
                    .toList();
        }

        int index = 0;
        if (body.get("currentMovieIndex") instanceof Number number) {
            index = number.intValue();
        }

        return new SessionSnapshot(participants, index, Boolean.TRUE.equals(body.get("votingStarted")));
    }

    public record SessionSnapshot(List<String> participants, int currentMovieIndex, boolean votingStarted) {
    }

    public static class StaleVoteException extends RuntimeException {
        public StaleVoteException(String message) {
            super(message);
        }
    }

    @Transactional(readOnly = true)
    public List<Vote> getAllVotesInSession(UUID sessionId) {
        return voteRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    public boolean updateMovieIndexInSession(UUID sessionId, int movieIndex) {
        try {
            String url = sessionServiceUrl + String.format(SESSION_UPDATE_INDEX_PATH, sessionId);

            HashMap<String, Object> body = new HashMap<>();
            body.put("currentMovieIndex", movieIndex);

            restTemplate.postForObject(url, new HttpEntity<>(body, internalHeaders()), Void.class);
            log.info("Индекс фильма обновлён: сессия={}, индекс={}", sessionId, movieIndex);
            return true;
        } catch (Exception e) {
            log.warn("Ошибка при обновлении индекса фильма: {}", e.getMessage());
            return false;
        }
    }

    public void finishSession(UUID sessionId) {
        try {
            String url = sessionServiceUrl + String.format(SESSION_FINISH_PATH, sessionId);
            restTemplate.postForObject(url, new HttpEntity<>(Map.of(), internalHeaders()), Void.class);
            log.info("Сессия завершена по совпадению: {}", sessionId);
        } catch (Exception e) {
            log.warn("Ошибка при завершении сессии: {}", e.getMessage());
        }
    }
}
