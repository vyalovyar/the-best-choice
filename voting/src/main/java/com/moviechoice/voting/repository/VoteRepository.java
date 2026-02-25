package com.moviechoice.voting.repository;


import com.moviechoice.voting.entity.Vote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoteRepository extends JpaRepository<Vote, UUID> {

    //Будем смоетреть все голоса в комнате
    List<Vote> findBySessionIdOrderByCreatedAtDesc(UUID sessionId);

    //Голос конкретного челика
    List<Vote> findBySessionIdAndParticipantId(UUID sessionId, UUID participantId);
}
