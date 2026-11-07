package com.moviechoice.voting.dto;

import com.moviechoice.voting.entity.Movie;

public record MovieResponse(
        Long id,
        String title,
        String overview,
        String posterPath,
        String genre,
        Double rating
) {

    public static MovieResponse from(Movie movie) {
        String genre = movie.getGenre();
        return new MovieResponse(
                movie.getId(),
                movie.getTitle(),
                movie.getOverview(),
                movie.getPosterPath(),
                genre == null || genre.isBlank() ? "" : genre,
                movie.getVoteAvg()
        );
    }
}
