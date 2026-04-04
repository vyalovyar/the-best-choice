package com.moviechoice.voting.dto;

import lombok.Data;
import java.util.UUID;

@Data
public class StartVotingRequest {
    private UUID sessionId;
    private String by;
}
