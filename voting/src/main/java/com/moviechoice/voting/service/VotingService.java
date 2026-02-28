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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
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


    @Transactional(readOnly = true)
    public List<Movie> getMoviesForVote() {
        // Проверка кеша
        boolean needRefresh = needsRefresh();
        
        if (needRefresh) {
            log.info("Кеша устарел, нужно обновить фильмы из тмдб");
            updateMoviesForTmdb();
        }

        return movieRepository.findAll();

    }

    // Отдельный метод для проверки нужнен ли рефреш
    private boolean needsRefresh() {
        boolean needRefresh = refreshCache();
        return needRefresh;
    }

    private boolean refreshCache() {
        ZonedDateTime proverka = ZonedDateTime.now().minusDays(1);
        List<Movie> expireMovies = movieRepository.findByCacheAtBefore(proverka);

        //Проверка на хоть один маленький фильм в бд
        if (movieRepository.count() == 0) {
            return true;
        }
        Optional<Movie> lastMovie = movieRepository.findTopByOrderByCacheAtDesc();

        return lastMovie.map(movie -> movie.getCacheAt().isBefore(proverka)).orElse(true);
    }

    //когда нужно обновить фильмецы - отдельная транзакция!
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void updateMoviesForTmdb(){
        TmdbMoviesResponseDto responseDto = tmdbClient.getPopularMovies();

        if(responseDto == null || responseDto.getResults()==null){
            log.warn("Пустой ответ от ТМБД");
            return;
        }

        int created = 0;
        int update = 0;

        for(TmdbMovieDto dto : responseDto.getResults()){
            Optional<Movie> existingMovie = movieRepository.findById(dto.getId());
            if(existingMovie.isPresent()){
                Movie movie = existingMovie.get();
                movie.setTitle(dto.getTitle());
                movie.setVoteAvg(dto.getVoteAverage());
                movie.setCacheAt(ZonedDateTime.now());
                movie.setPosterPath(dto.getPosterPath());
                movieRepository.save(movie);
                update++;
            }else{
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
        log.info("Обновление завершилось, обновлено={}, создано={}", update, created);
    }
    //создаем голос если есть фильм и тд и тп
    @Transactional
    public Vote createVote(UUID sessionId, UUID participantId, Long movieId, VoteDecision decision){
        Movie movi = movieRepository.findById(movieId).orElseThrow(() -> new RuntimeException("Фильм не найден: " + movieId));

        Vote vote = Vote.builder().sessionId(sessionId).participantId(participantId).movie(movi).decision(decision).createdAt(ZonedDateTime.now()).build();
        log.info("голос участника={}, по фильму = {}, его голос = {} ", participantId, movieId, decision);
        return voteRepository.save(vote);
    }

    //Собираем голоса всех, нам важен ВАШ голос!
    @Transactional(readOnly = true)
    public List<Vote> getAllVotesInSession(UUID sessionId){
        return voteRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
    }


}

