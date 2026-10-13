package com.moviechoice.voting.repository;

import com.moviechoice.voting.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    //поиск по ид
    Optional<Movie> findById(Long movieId);

    boolean existsById(Long Id);
    boolean existsByOverviewIsNull();

    Optional<Movie> findTopByOrderByCacheAtDesc();

}
