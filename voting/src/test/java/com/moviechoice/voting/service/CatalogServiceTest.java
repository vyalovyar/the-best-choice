package com.moviechoice.voting.service;

import com.moviechoice.voting.entity.CatalogState;
import com.moviechoice.voting.repository.CatalogStateRepository;
import com.moviechoice.voting.repository.MovieRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogServiceTest {

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private CatalogStateRepository catalogStateRepository;

    @Mock
    private MovieCacheService movieCacheService;

    @InjectMocks
    private CatalogService catalogService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(catalogService, "maxPages", 500);
    }

    @Test
    void ensureHasMovieAtReturnsTrueWithoutFetchingWhenIndexAlreadyExists() {
        when(movieRepository.count()).thenReturn(20L);

        boolean available = catalogService.ensureHasMovieAt(19);

        assertThat(available).isTrue();
        verify(movieCacheService, never()).appendMoviesFromPage(anyInt());
    }

    @Test
    void ensureHasMovieAtFetchesNextTmdbPageUntilIndexAppears() {
        when(movieRepository.count()).thenReturn(20L, 20L, 40L);
        when(catalogStateRepository.findById(CatalogState.SINGLETON_ID))
                .thenReturn(Optional.of(CatalogState.builder()
                        .id(CatalogState.SINGLETON_ID).nextPage(2).exhausted(false).build()));
        when(movieCacheService.appendMoviesFromPage(2)).thenReturn(20);
        when(catalogStateRepository.save(any(CatalogState.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean available = catalogService.ensureHasMovieAt(25);

        assertThat(available).isTrue();
        verify(movieCacheService).appendMoviesFromPage(2);
    }

    @Test
    void ensureHasMovieAtReturnsFalseWhenTmdbIsExhausted() {
        when(movieRepository.count()).thenReturn(20L);
        when(catalogStateRepository.findById(CatalogState.SINGLETON_ID))
                .thenReturn(Optional.of(CatalogState.builder()
                        .id(CatalogState.SINGLETON_ID).nextPage(2).exhausted(true).build()));

        boolean available = catalogService.ensureHasMovieAt(20);

        assertThat(available).isFalse();
        verify(movieCacheService, never()).appendMoviesFromPage(anyInt());
    }

    @Test
    void ensureHasMovieAtStopsAtMaxPages() {
        ReflectionTestUtils.setField(catalogService, "maxPages", 2);
        when(movieRepository.count()).thenReturn(20L);
        when(catalogStateRepository.findById(CatalogState.SINGLETON_ID))
                .thenReturn(Optional.of(CatalogState.builder()
                        .id(CatalogState.SINGLETON_ID).nextPage(3).exhausted(false).build()));
        when(catalogStateRepository.save(any(CatalogState.class))).thenAnswer(inv -> inv.getArgument(0));

        boolean available = catalogService.ensureHasMovieAt(50);

        assertThat(available).isFalse();
        verify(movieCacheService, never()).appendMoviesFromPage(anyInt());
    }

    @Test
    void resetPagingClearsExhaustionAndRestartsFromSecondPage() {
        CatalogState exhausted = CatalogState.builder()
                .id(CatalogState.SINGLETON_ID).nextPage(500).exhausted(true).build();
        when(catalogStateRepository.findById(CatalogState.SINGLETON_ID)).thenReturn(Optional.of(exhausted));
        when(catalogStateRepository.save(any(CatalogState.class))).thenAnswer(inv -> inv.getArgument(0));

        catalogService.resetPaging();

        assertThat(exhausted.getNextPage()).isEqualTo(2);
        assertThat(exhausted.getExhausted()).isFalse();
    }
}
