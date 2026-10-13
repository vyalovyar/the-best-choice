package com.moviechoice.voting.repository;


import com.moviechoice.voting.entity.Vote;
import com.moviechoice.voting.entity.VoteDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VoteRepository extends JpaRepository<Vote, UUID> {

    List<Vote> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    List<Vote> findBySessionIdAndParticipantId(UUID sessionId, String participantId);

    Optional<Vote> findBySessionIdAndParticipantIdAndMovie_Id(UUID sessionId, String participantId, Long movieId);

    @Query("select count(distinct v.participantId) from Vote v where v.sessionId = :sessionId and v.movie.id = :movieId and v.decision = :decision")
    long countDistinctParticipants(@Param("sessionId") UUID sessionId, @Param("movieId") Long movieId, @Param("decision") VoteDecision decision);
}
