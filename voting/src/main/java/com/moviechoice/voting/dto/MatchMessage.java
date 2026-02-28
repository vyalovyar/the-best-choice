package com.moviechoice.voting.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MatchMessage {
    private UUID sessionId;
    private Long movieId;
    private String movieTitle;
    private String posterPath;
    private String message;
}
