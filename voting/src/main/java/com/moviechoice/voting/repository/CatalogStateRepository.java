package com.moviechoice.voting.repository;

import com.moviechoice.voting.entity.CatalogState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CatalogStateRepository extends JpaRepository<CatalogState, Long> {
}
