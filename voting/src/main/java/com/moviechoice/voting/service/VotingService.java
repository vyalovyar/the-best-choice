package com.moviechoice.voting.service;

import com.moviechoice.voting.client.TmdbClient;
import com.moviechoice.voting.dto.VoteOutcome;
import com.moviechoice.voting.dto.tmld.TmdbMovieDto;
import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class VotingService {

    private final TmdbClient tmdbClient;
    private final MovieRepository movieRepository;
    private final VoteRepository voteRepository;
    private final RestTemplate restTemplate;

    @Value("${session.service.url:http://localhost:8081}")
    private String sessionServiceUrl;

    private static final String SESSION_UPDATE_INDEX_PATH = "/api/sessions/%s/update-index";

    private final Object refreshLock = new Object();
    private final Object lockStub = new Object();
    private final java.util.concurrent.ConcurrentHashMap<UUID, Object> sessionLocks =
            new java.util.concurrent.ConcurrentHashMap<>();


    public List<Movie> getMoviesForVote() {
        if (needsRefresh()) {
            synchronized (refreshLock) {
                if (needsRefresh()) {
                    log.info("Кеш устарел, обновляем фильмы из TMDB");
                    updateMoviesFromTmdb();
                }
            }
        }
        return movieRepository.findAll(Sort.by("id"));
    }

    // метод только проверяет, нужно ли обновлять
    private boolean needsRefresh() {
        if (movieRepository.count() == 0) {
            return true;
        }
        ZonedDateTime oneDayAgo = ZonedDateTime.now().minusDays(1);
        return movieRepository.findTopByOrderByCacheAtDesc()
                .map(m -> m.getCacheAt().isBefore(oneDayAgo))
                .orElse(true);
    }

    @Transactional
    public void updateMoviesFromTmdb() {
        TmdbMoviesResponseDto responseDto = tmdbClient.getPopularMovies();

        if (responseDto == null || responseDto.getResults() == null) {
            log.warn("Пустой ответ от TMDB");
            return;
        }

        int created = 0;
        int updated = 0;

        for (TmdbMovieDto dto : responseDto.getResults()) {
            Optional<Movie> existingMovie = movieRepository.findById(dto.getId());

            if (existingMovie.isPresent()) {
                Movie movie = existingMovie.get();
                movie.setTitle(dto.getTitle());
                movie.setOverview(dto.getOverview());
                movie.setVoteAvg(dto.getVoteAverage());
                movie.setCacheAt(ZonedDateTime.now());
                movie.setPosterPath(dto.getPosterPath());
                movieRepository.save(movie);
                updated++;
            } else {
                Movie movie = Movie.builder()
                        .id(dto.getId())
                        .title(dto.getTitle())
                        .posterPath(dto.getPosterPath())
                        .overview(dto.getOverview())
                        .voteAvg(dto.getVoteAverage())
                        .cacheAt(ZonedDateTime.now())
                        .build();
                movieRepository.save(movie);
                created++;
            }
        }
        log.info("Обновление фильмов завершено: создано={}, обновлено={}", created, updated);
    }

    //Создание голоса
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
        // один раунд сессии обрабатывается строго по очереди
        synchronized (sessionLocks.computeIfAbsent(sessionId, id -> new Object())) {
            List<String> participants = getParticipantIds(sessionId);
            if (participants.size() < 2) {
                throw new IllegalStateException("Для голосования нужно минимум 2 участника");
            }
            if (!participants.contains(participantId)) {
                throw new IllegalStateException("Участник не найден в сессии");
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
                return new VoteOutcome(vote, voted, total, VoteOutcome.Status.MATCH, null);
            }

            List<Long> ids = movieRepository.findAllIdsOrdered();
            int next = ids.isEmpty() ? 0 : (ids.indexOf(movieId) + 1) % ids.size();
            updateMovieIndexInSession(sessionId, next);
            return new VoteOutcome(vote, voted, total, VoteOutcome.Status.NEXT, next);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> getParticipantIds(UUID sessionId) {
        String url = sessionServiceUrl + "/api/sessions/" + sessionId + "/participants";
        java.util.Map<String, Object> body = restTemplate.getForObject(url, java.util.Map.class);
        if (body == null || !(body.get("participants") instanceof List<?> raw)) {
            throw new IllegalStateException("Не удалось получить участников сессии");
        }
        return ((List<java.util.Map<String, Object>>) raw).stream()
                .map(p -> String.valueOf(p.get("id")))
                .toList();
    }

    // получение всех голосов в сессии, нам важен ВАШ голос
    @Transactional(readOnly = true)
    public List<Vote> getAllVotesInSession(UUID sessionId) {
        return voteRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }

    // обновление индекса фильма в сессии
    public void updateMovieIndexInSession(UUID sessionId, int movieIndex) {
        try {
            String url = sessionServiceUrl + String.format(SESSION_UPDATE_INDEX_PATH, sessionId);

            HashMap<String, Object> body = new HashMap<>();
            body.put("currentMovieIndex", movieIndex);

            restTemplate.postForObject(url, body, Void.class);
            log.info("Индекс фильма обновлён: сессия={}, индекс={}", sessionId, movieIndex);
        } catch (Exception e) {
            log.warn("Ошибка при обновлении индекса фильма (не критично): {}", e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public long countLikes(UUID sessionId, Long movieId) {
        return voteRepository.countDistinctParticipants(sessionId, movieId, VoteDecision.LIKE);
    }
}
