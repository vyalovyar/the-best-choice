package com.moviechoice.session.entity;

import lombok.*;
import jakarta.persistence.*;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "participants", indexes = {
        @Index(name = "idx_participants_session", columnList = "session_id"),
        @Index(name = "idx_participants_last_seen", columnList = "last_seen")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Participant {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @Column(name = "participant_name", nullable = false)
    private String name;

    @Column(name = "joined_at", nullable = false)
    private ZonedDateTime joinedAt;

    @Column(name = "last_seen")
    private ZonedDateTime lastSeen;

    @Builder.Default
    @Column(name = "is_host")
    private Boolean host = false;

    @Column(name = "token", length = 64)
    private String token;

    public boolean isHost() {
        return Boolean.TRUE.equals(host);
    }
}
