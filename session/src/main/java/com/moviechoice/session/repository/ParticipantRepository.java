package com.moviechoice.session.repository;

import com.moviechoice.session.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
    @Query("select p from Participant p where p.session.id = :sessionId order by p.joinedAt asc")
    List<Participant> findAllBySessionId(@Param("sessionId") UUID sessionId);
}
