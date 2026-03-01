package com.moviechoice.voting.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UpdateMovieIndexRequest {
    private UUID sessionId;
    private Integer movieIndex;
}
