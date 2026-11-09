package com.moviechoice.session.repository;

import com.moviechoice.session.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, UUID> {
    @Query("select p from Participant p where p.session.id = :sessionId order by p.joinedAt asc")
    List<Participant> findAllBySessionId(@Param("sessionId") UUID sessionId);

    @Query("select count(p) > 0 from Participant p where p.session.id = :sessionId and lower(p.name) = lower(:name)")
    boolean existsBySessionIdAndNameIgnoreCase(@Param("sessionId") UUID sessionId, @Param("name") String name);

    @Modifying
    @Query("update Participant p set p.lastSeen = :now where p.id = :participantId")
    int touch(@Param("participantId") UUID participantId, @Param("now") ZonedDateTime now);

    @Query("select p from Participant p where p.lastSeen is null or p.lastSeen < :threshold")
    List<Participant> findStale(@Param("threshold") ZonedDateTime threshold);
}
