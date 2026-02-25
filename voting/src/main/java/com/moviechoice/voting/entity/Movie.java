package com.moviechoice.voting.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.ZonedDateTime;


@Entity
@Table(name = "movies", schema = "schema_voting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Movie {

    @Id
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(length = 255)
    private String posterPath;

    @Column
    private Double voteAvg;

    @Column(nullable = false)
    private ZonedDateTime cacheAt;
}
