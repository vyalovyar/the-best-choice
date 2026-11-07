package com.moviechoice.voting.service;

import com.moviechoice.voting.client.TmdbClient;
import com.moviechoice.voting.dto.tmld.TmdbMovieDto;
import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
import com.moviechoice.voting.entity.Movie;
import com.moviechoice.voting.repository.MovieRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MovieCacheServiceTest {

    @Mock
    private TmdbClient tmdbClient;

    @Mock
    private MovieRepository movieRepository;

    @InjectMocks
    private MovieCacheService movieCacheService;

    @Test
    void appendMoviesFromPageSkipsMoviesAlreadyStored() {
        TmdbMoviesResponseDto response = new TmdbMoviesResponseDto();
        response.setResults(List.of(dto(1L, "One"), dto(2L, "Two"), dto(3L, "Three")));

        when(tmdbClient.getPopularMovies(2)).thenReturn(response);
        when(movieRepository.findMaxCatalogPosition()).thenReturn(19L);
        when(movieRepository.existsById(1L)).thenReturn(true);
        when(movieRepository.existsById(2L)).thenReturn(false);
        when(movieRepository.existsById(3L)).thenReturn(false);
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        int added = movieCacheService.appendMoviesFromPage(2);

        List<Movie> saved = savedMovies();
        assertThat(added).isEqualTo(2);
        assertThat(saved).extracting(Movie::getId).containsExactly(2L, 3L);
        assertThat(saved).extracting(Movie::getCatalogPosition).containsExactly(20L, 21L);
    }

    @Test
    void appendMoviesFromPageDedupesIdsInsideOnePage() {
        TmdbMoviesResponseDto response = new TmdbMoviesResponseDto();
        response.setResults(List.of(dto(7L, "Seven"), dto(7L, "Seven duplicate")));

        when(tmdbClient.getPopularMovies(3)).thenReturn(response);
        when(movieRepository.findMaxCatalogPosition()).thenReturn(4L);
        when(movieRepository.existsById(7L)).thenReturn(false);
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        int added = movieCacheService.appendMoviesFromPage(3);

        assertThat(added).isEqualTo(1);
        assertThat(savedMovies()).extracting(Movie::getCatalogPosition).containsExactly(5L);
    }

    @Test
    void assignMissingPositionsBackfillsOldRows() {
        when(movieRepository.findIdsWithoutCatalogPosition()).thenReturn(List.of(11L, 12L));
        when(movieRepository.findMaxCatalogPosition()).thenReturn(2L);
        when(movieRepository.findById(11L)).thenReturn(java.util.Optional.of(Movie.builder().id(11L).build()));
        when(movieRepository.findById(12L)).thenReturn(java.util.Optional.of(Movie.builder().id(12L).build()));
        when(movieRepository.save(any(Movie.class))).thenAnswer(inv -> inv.getArgument(0));

        movieCacheService.assignMissingPositions();

        assertThat(savedMovies()).extracting(Movie::getCatalogPosition).containsExactly(3L, 4L);
    }

    @Test
    void appendMoviesFromPageDoesNothingWhenTmdbReturnsNull() {
        when(tmdbClient.getPopularMovies(4)).thenReturn(null);

        int added = movieCacheService.appendMoviesFromPage(4);

        assertThat(added).isZero();
        verify(movieRepository, never()).save(any(Movie.class));
    }

    private List<Movie> savedMovies() {
        ArgumentCaptor<Movie> captor = ArgumentCaptor.forClass(Movie.class);
        verify(movieRepository, atLeast(1)).save(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private static TmdbMovieDto dto(Long id, String title) {
        TmdbMovieDto dto = new TmdbMovieDto();
        dto.setId(id);
        dto.setTitle(title);
        return dto;
    }
}
