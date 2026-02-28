package com.moviechoice.voting.config;

import com.moviechoice.voting.service.VotingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

//Я подумал, что ленивая загрузка не очень, поэтому пусть будет так
@Component
@RequiredArgsConstructor
@Slf4j
public class MovieInitializer {

    private final VotingService votingService;

    //Предзагрузка фильмов в бд
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMovies() {
        log.info("ИНИЦИАЛИЗАЦИЯ ФИЛЬМОВ ПРИ СТАРТЕ ПРИЛОЖЕНИЯ");
        try {
            votingService.updateMoviesForTmdb();
            log.info("ФИЛЬМЫ УСПЕШНО ЗАГРУЖЕНЫ ПРИ СТАРТЕ");
        } catch (Exception e) {
            log.error("Ошибка при инициализации фильмов: ", e);
            // Не выбрасываем исключение, чтобы приложение все равно запустилось
        }
    }
}
