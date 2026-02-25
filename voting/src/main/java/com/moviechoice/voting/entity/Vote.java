package com.moviechoice.voting.entity;


import jakarta.persistence.*;
import lombok.*;

import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "votes", schema = "schema_voting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID sessionId;

    @Column(nullable = false)
    private UUID patricipanId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "movi_id", nullable = false)
    private Movie movie;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private VoteDecision decision;

    @Column(nullable = false)
    private ZonedDateTime createdAt;

}
