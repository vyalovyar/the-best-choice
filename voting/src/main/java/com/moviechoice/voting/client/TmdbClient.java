package com.moviechoice.voting.client;


import com.moviechoice.voting.dto.tmld.TmdbMoviesResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class TmdbClient {

    private final RestTemplate restTemplate;

    @Value("${tmdb.base-url}")
    private String baseUrl;

    @Value("${tmdb.api-key}")
    private String apiKey;

    //достаем популярные фильмы через апишку
    public TmdbMoviesResponseDto getPopularMovies(){
        String url = String.format("%s/movie/popular?api_key=%s&language=ru-RU&page=1", baseUrl, apiKey);
        log.info("достаем фильмы");
        //отправляем посыльного за фильмами
        return restTemplate.getForObject(url, TmdbMoviesResponseDto.class);
    }


}
