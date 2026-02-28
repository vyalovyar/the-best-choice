package com.moviechoice.voting.dto;

import com.moviechoice.voting.entity.VoteDecision;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZonedDateTime;
import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class VoteMessage {
    private UUID voteId;
    private UUID sessionId;
    private UUID participantId;
    private Long movieId;
    private String movieTitle;
    private VoteDecision decision;
    private ZonedDateTime timestamp;
    private String type;
}
