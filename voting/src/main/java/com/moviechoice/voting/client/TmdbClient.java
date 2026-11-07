package com.moviechoice.voting.client;


import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class TmdbClient {

    private final RestTemplate restTemplate;

    @Value("${tmdb.base-url}")
    private String baseUrl;

    @Value("${tmdb.token}")
    private String token;

    public TmdbMoviesResponseDto getPopularMovies() {
        return getPopularMovies(1);
    }

    public TmdbMoviesResponseDto getPopularMovies(int page) {
        int safePage = Math.max(1, page);
        String url = baseUrl + "/movie/popular?language=ru-RU&page=" + safePage;
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        log.info("Запрашиваем популярные фильмы у TMDB, страница {}", safePage);
        return restTemplate
                .exchange(url, HttpMethod.GET, new HttpEntity<>(headers), TmdbMoviesResponseDto.class)
                .getBody();
    }
}
