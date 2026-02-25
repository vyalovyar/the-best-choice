package com.moviechoice.voting.dto.tmld;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
//Чтобы не вылетала ошибка ловим ток то, что нужно
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbMovieDto {

    @JsonProperty("id")
    private Long id;

    @JsonProperty("title")
    private String title;

    @JsonProperty("poster_path")
    private String posterPath;

    @JsonProperty("vote_average")
    private Double voteAverage;


}
