package com.moviechoice.voting.config;

import com.moviechoice.voting.service.VotingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MovieInitializer {

    private final VotingService votingService;
    @Value("${movies.preload-on-startup:false}")
    private boolean preloadOnStartup;

    //Предзагрузка фильмов в бд
    @EventListener(ApplicationReadyEvent.class)
    public void initializeMovies() {
        if (!preloadOnStartup) {
            log.info("ПРЕДЗАГРУЗКА ФИЛЬМОВ ПРИ СТАРТЕ ОТКЛЮЧЕНА");
            return;
        }
        log.info("ИНИЦИАЛИЗАЦИЯ ФИЛЬМОВ ПРИ СТАРТЕ ПРИЛОЖЕНИЯ");
        try {
            votingService.updateMoviesFromTmdb();
            log.info("ФИЛЬМЫ УСПЕШНО ЗАГРУЖЕНЫ ПРИ СТАРТЕ");
        } catch (Exception e) {
            log.error("Ошибка при инициализации фильмов: ", e);
            // Не выбрасываем исключение, чтобы приложение все равно запустилось
        }
    }
}
