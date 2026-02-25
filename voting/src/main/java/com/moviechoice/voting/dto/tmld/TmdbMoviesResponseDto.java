package com.moviechoice.voting.dto.tmld;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;


//Класс для распаршивания джонсчика без ошиьки
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmdbMoviesResponseDto {

    @JsonProperty("results")
    private List<TmdbMovieDto> results;
}
