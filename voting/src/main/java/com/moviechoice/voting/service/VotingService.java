package com.moviechoice.voting.service;

import com.moviechoice.voting.client.TmdbClient;
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


    @Transactional(readOnly = true)
    public List<Movie> getMoviesForVote() {
        if (needsRefresh()) {
            log.info("Кеш устарел, обновляем фильмы из TMDB");
            updateMoviesFromTmdb();
        }
        return movieRepository.findAll();
    }

    // метод только проверяет, нужно ли обновлять
    private boolean needsRefresh() {
        // Если в БД нет фильмов - точно нужно обновить
        if (movieRepository.count() == 0) {
            return true;
        }

        // проверяем, когда последний раз обновляли кеш
        ZonedDateTime oneDayAgo = ZonedDateTime.now().minusDays(1);
        Optional<Movie> lastUpdated = movieRepository.findTopByOrderByCacheAtDesc();

        return lastUpdated.map(movie -> movie.getCacheAt().isBefore(oneDayAgo))
                .orElse(true);
    }

    // отдельная транзакция для обновления
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        Movie movie = movieRepository.findById(movieId)  // ✅ Исправил: movi → movie
                .orElseThrow(() -> new RuntimeException("Фильм не найден: " + movieId));

        Vote vote = Vote.builder()
                .sessionId(sessionId)
                .participantId(participantId)
                .movie(movie)
                .decision(decision)
                .createdAt(ZonedDateTime.now())
                .build();

        log.info("Голос сохранён: участник={}, фильм={}, решение={}",
                participantId, movieId, decision);

        return voteRepository.save(vote);
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
            // не выбрасываю исключение, чтобы не ломат голосование
        }
    }
}