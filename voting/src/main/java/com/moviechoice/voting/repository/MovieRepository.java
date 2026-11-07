package com.moviechoice.voting.repository;

import com.moviechoice.voting.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    Optional<Movie> findById(Long movieId);

    boolean existsById(Long Id);
    boolean existsByOverviewIsNull();

    Optional<Movie> findTopByOrderByCacheAtDesc();

    @Query("select m.id from Movie m order by m.catalogPosition asc, m.id asc")
    List<Long> findAllIdsOrdered();

    @Query("select m.id from Movie m where m.catalogPosition is null order by m.id asc")
    List<Long> findIdsWithoutCatalogPosition();

    @Query("select coalesce(max(m.catalogPosition), -1) from Movie m")
    long findMaxCatalogPosition();
}
