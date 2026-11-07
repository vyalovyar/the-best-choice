package com.moviechoice.voting.service;

import com.moviechoice.voting.client.TmdbClient;
import com.moviechoice.voting.dto.tmld.TmdbMovieDto;
import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.genre.GenreCatalog;
import com.moviechoice.voting.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class MovieCacheService {

    private final TmdbClient tmdbClient;
    private final MovieRepository movieRepository;

    @Transactional
    public int appendMoviesFromPage(int page) {
        TmdbMoviesResponseDto response = tmdbClient.getPopularMovies(page);
        if (response == null || response.getResults() == null) {
            log.warn("Пустой ответ от TMDB, страница {}", page);
            return 0;
        }

        long maxPosition = movieRepository.findMaxCatalogPosition();
        int added = 0;

        for (TmdbMovieDto dto : distinctById(response.getResults())) {
            if (movieRepository.existsById(dto.getId())) {
                continue;
            }
            maxPosition++;
            movieRepository.save(toMovie(dto, maxPosition));
            added++;
        }

        log.info("Добавлено фильмов из TMDB (страница {}): {}", page, added);
        return added;
    }

    @Transactional
    public void refreshMovies() {
        TmdbMoviesResponseDto response = tmdbClient.getPopularMovies(1);
        if (response == null || response.getResults() == null) {
            log.warn("Пустой ответ от TMDB");
            return;
        }

        long maxPosition = movieRepository.findMaxCatalogPosition();

        for (TmdbMovieDto dto : distinctById(response.getResults())) {
            Movie existing = movieRepository.findById(dto.getId()).orElse(null);
            if (existing == null) {
                maxPosition++;
                movieRepository.save(toMovie(dto, maxPosition));
            } else {
                applyFields(existing, dto);
                movieRepository.save(existing);
            }
        }
    }

    @Transactional
    public void assignMissingPositions() {
        List<Long> ids = movieRepository.findIdsWithoutCatalogPosition();
        if (ids.isEmpty()) {
            return;
        }

        long maxPosition = movieRepository.findMaxCatalogPosition();
        for (Long id : ids) {
            Movie movie = movieRepository.findById(id).orElse(null);
            if (movie == null) {
                continue;
            }
            maxPosition++;
            movie.setCatalogPosition(maxPosition);
            movieRepository.save(movie);
        }
        log.info("Проставлены позиции каталога для {} фильмов", ids.size());
    }

    private List<TmdbMovieDto> distinctById(List<TmdbMovieDto> dtos) {
        Map<Long, TmdbMovieDto> byId = new LinkedHashMap<>();
        for (TmdbMovieDto dto : dtos) {
            if (dto == null || dto.getId() == null) {
                continue;
            }
            byId.putIfAbsent(dto.getId(), dto);
        }
        return List.copyOf(byId.values());
    }

    private Movie toMovie(TmdbMovieDto dto, long position) {
        return Movie.builder()
                .id(dto.getId())
                .title(dto.getTitle())
                .posterPath(dto.getPosterPath())
                .overview(dto.getOverview())
                .voteAvg(dto.getVoteAverage())
                .genre(GenreCatalog.toRussian(dto.getGenreIds()))
                .catalogPosition(position)
                .cacheAt(ZonedDateTime.now())
                .build();
    }

    private void applyFields(Movie movie, TmdbMovieDto dto) {
        movie.setTitle(dto.getTitle());
        movie.setOverview(dto.getOverview());
        movie.setVoteAvg(dto.getVoteAverage());
        movie.setGenre(GenreCatalog.toRussian(dto.getGenreIds()));
        movie.setPosterPath(dto.getPosterPath());
        movie.setCacheAt(ZonedDateTime.now());
    }
}
