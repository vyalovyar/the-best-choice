package com.moviechoice.voting.dto;

import com.moviechoice.voting.entity.Vote;

public record VoteOutcome(Vote vote, long voted, int total, Status status, Integer nextMovieIndex) {
    public enum Status { WAITING, MATCH, NEXT }
}