package com.moviechoice.voting.service;

import com.moviechoice.voting.entity.CatalogState;
import com.moviechoice.voting.repository.CatalogStateRepository;
import com.moviechoice.voting.repository.MovieRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogService {

    private static final int MAX_PAGES_PER_CALL = 10;

    private final MovieRepository movieRepository;
    private final CatalogStateRepository catalogStateRepository;
    private final MovieCacheService movieCacheService;

    @Value("${movies.max-pages:500}")
    private int maxPages = 500;

    private final Object appendLock = new Object();

    public boolean hasMovieAt(int index) {
        if (index < 0) {
            return true;
        }
        return index < movieRepository.count();
    }

    public int catalogSize() {
        return (int) movieRepository.count();
    }

    public boolean ensureHasMovieAt(int index) {
        if (hasMovieAt(index)) {
            return true;
        }

        synchronized (appendLock) {
            for (int attempt = 0; attempt < MAX_PAGES_PER_CALL; attempt++) {
                if (hasMovieAt(index)) {
                    return true;
                }

                CatalogState state = loadState();
                if (Boolean.TRUE.equals(state.getExhausted())) {
                    return false;
                }
                if (state.getNextPage() > maxPages) {
                    markExhausted(state);
                    return false;
                }

                int page = state.getNextPage();
                int added = movieCacheService.appendMoviesFromPage(page);

                state.setNextPage(page + 1);
                state.setUpdatedAt(ZonedDateTime.now());
                catalogStateRepository.save(state);

                if (added == 0) {
                    log.info("Страница TMDB {} не дала новых фильмов, продолжаем со следующей", page);
                }
            }

            return hasMovieAt(index);
        }
    }

    @Transactional
    public void resetPaging() {
        CatalogState state = loadState();
        state.setNextPage(2);
        state.setExhausted(false);
        state.setUpdatedAt(ZonedDateTime.now());
        catalogStateRepository.save(state);
    }

    private CatalogState loadState() {
        return catalogStateRepository.findById(CatalogState.SINGLETON_ID)
                .orElseGet(() -> catalogStateRepository.save(CatalogState.builder()
                        .id(CatalogState.SINGLETON_ID)
                        .nextPage(2)
                        .exhausted(false)
                        .updatedAt(ZonedDateTime.now())
                        .build()));
    }

    private void markExhausted(CatalogState state) {
        log.info("Каталог фильмов исчерпан на странице {}", state.getNextPage());
        state.setExhausted(true);
        state.setUpdatedAt(ZonedDateTime.now());
        catalogStateRepository.save(state);
    }
}
