package com.moviechoice.voting.dto;


import com.moviechoice.voting.entity.VoteDecision;
import lombok.Data;

import java.util.UUID;

@Data
public class VoteRequest {
    private UUID sessionId;
    private UUID participantId;
    private Long movieId;
    private VoteDecision decision;
}
