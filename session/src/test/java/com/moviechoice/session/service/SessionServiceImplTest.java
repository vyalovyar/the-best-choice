package com.moviechoice.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.repository.ParticipantRepository;
import com.moviechoice.session.repository.SessionRepository;
import com.moviechoice.session.service.SessionServiceImpl;

@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ParticipantRepository participantRepository;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        sessionService = new SessionServiceImpl(sessionRepository, participantRepository, Duration.ofSeconds(25));
    }

    @Test
    void createSessionBuildsActiveSessionWithStrongCode() {
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Session created = sessionService.createSession();

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());
        Session saved = captor.getValue();

        assertThat(created).isSameAs(saved);
        assertThat(saved.getCode()).matches("[A-HJ-NP-Z2-9]{8}");
        assertThat(saved.getCode()).doesNotContain("I", "O", "0", "1");
        assertThat(saved.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(saved.getCurrentMovieIndex()).isZero();
        assertThat(saved.getVotingStarted()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void createSessionRetriesWhenCodeAlreadyExists() {
        when(sessionRepository.existsByCode(any())).thenReturn(true, false);
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Session created = sessionService.createSession();

        assertThat(created.getCode()).matches("[A-HJ-NP-Z2-9]{8}");
    }

    @Test
    void addParticipantNormalizesNameAndStartsLifetime() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(participantRepository.save(any(Participant.class))).thenAnswer(i -> i.getArgument(0));

        Participant participant = sessionService.addParticipant(sessionId, "  Alice  ", false);

        assertThat(participant.getName()).isEqualTo("Alice");
        assertThat(participant.getSession()).isSameAs(session);
        assertThat(participant.getJoinedAt()).isNotNull();
        assertThat(participant.getLastSeen()).isNotNull();
        assertThat(participant.getToken()).isNotBlank();
        assertThat(participant.isHost()).isFalse();
    }

    @Test
    void addParticipantMarksHostFlag() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));
        when(participantRepository.save(any(Participant.class))).thenAnswer(i -> i.getArgument(0));

        Participant host = sessionService.addParticipant(sessionId, "Alice", true);

        assertThat(host.isHost()).isTrue();
    }

    @Test
    void addParticipantIssuesDistinctTokens() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));
        when(participantRepository.save(any(Participant.class))).thenAnswer(i -> i.getArgument(0));

        String first = sessionService.addParticipant(sessionId, "Alice", false).getToken();
        String second = sessionService.addParticipant(sessionId, "Bob", false).getToken();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void addParticipantFallsBackToGuestForBlankName() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));
        when(participantRepository.save(any(Participant.class))).thenAnswer(i -> i.getArgument(0));

        Participant participant = sessionService.addParticipant(sessionId, "   ", false);

        assertThat(participant.getName()).isEqualTo("Guest");
    }

    @Test
    void addParticipantReturnsNullWhenSessionMissing() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        Participant participant = sessionService.addParticipant(sessionId, "Alice", false);

        assertThat(participant).isNull();
        verify(participantRepository, never()).save(any(Participant.class));
    }

    @Test
    void heartbeatTouchesParticipantOfThisSession() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Optional.of(
                Participant.builder().id(participantId).session(Session.builder().id(sessionId).build()).build()));

        boolean alive = sessionService.heartbeat(sessionId, participantId);

        assertThat(alive).isTrue();
        verify(participantRepository).touch(eq(participantId), any(ZonedDateTime.class));
    }

    @Test
    void heartbeatIgnoresParticipantFromForeignSession() {
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Optional.of(
                Participant.builder().id(participantId).session(Session.builder().id(UUID.randomUUID()).build()).build()));

        boolean alive = sessionService.heartbeat(UUID.randomUUID(), participantId);

        assertThat(alive).isFalse();
        verify(participantRepository, never()).touch(any(), any());
    }

    @Test
    void heartbeatRejectsNullParticipantId() {
        assertThat(sessionService.heartbeat(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    void isHostChecksOwnershipAndFlag() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        when(participantRepository.findById(hostId)).thenReturn(Optional.of(
                Participant.builder().id(hostId).host(true).session(Session.builder().id(sessionId).build()).build()));

        assertThat(sessionService.isHost(sessionId, hostId)).isTrue();
        assertThat(sessionService.isHost(UUID.randomUUID(), hostId)).isFalse();
        assertThat(sessionService.isHost(sessionId, null)).isFalse();
    }

    @Test
    void findByTokenMatchesOnlyOwnToken() {
        UUID sessionId = UUID.randomUUID();
        Participant alice = Participant.builder().id(UUID.randomUUID()).token("token-a").build();
        Participant bob = Participant.builder().id(UUID.randomUUID()).token("token-b").build();
        when(participantRepository.findAllBySessionId(sessionId)).thenReturn(List.of(alice, bob));

        assertThat(sessionService.findByToken(sessionId, "token-b")).contains(bob);
        assertThat(sessionService.findByToken(sessionId, "missing")).isEmpty();
        assertThat(sessionService.findByToken(sessionId, " ")).isEmpty();
    }

    @Test
    void pruneStaleParticipantsDropsIdleOnes() {
        UUID sessionId = UUID.randomUUID();
        Participant ghost = Participant.builder()
                .id(UUID.randomUUID())
                .name("Ghost")
                .session(Session.builder().id(sessionId).build())
                .build();
        when(participantRepository.findStale(any(ZonedDateTime.class))).thenReturn(List.of(ghost));

        int removed = sessionService.pruneStaleParticipants();

        assertThat(removed).isEqualTo(1);
        verify(participantRepository).deleteById(ghost.getId());
    }

    @Test
    void removeParticipantDeletesAndReturnsIt() {
        UUID participantId = UUID.randomUUID();
        Participant participant = Participant.builder()
                .id(participantId)
                .name("Alice")
                .session(Session.builder().id(UUID.randomUUID()).build())
                .build();
        when(participantRepository.findById(participantId)).thenReturn(Optional.of(participant));

        Participant removed = sessionService.removeParticipant(participantId).orElseThrow();

        assertThat(removed.getId()).isEqualTo(participantId);
        verify(participantRepository).deleteById(participantId);
    }

    @Test
    void removeParticipantReportsMissingParticipant() {
        UUID participantId = UUID.randomUUID();
        when(participantRepository.findById(participantId)).thenReturn(Optional.empty());

        assertThat(sessionService.removeParticipant(participantId)).isEmpty();
        verify(participantRepository, never()).deleteById(any());
    }

    @Test
    void updateMovieIndexPersistsNewIndex() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).currentMovieIndex(0).build()));
        when(sessionRepository.save(any(Session.class))).thenAnswer(i -> i.getArgument(0));

        Session updated = sessionService.updateMovieIndex(sessionId, 3).orElseThrow();

        assertThat(updated.getCurrentMovieIndex()).isEqualTo(3);
        assertThat(updated.getUpdatedAt()).isNotNull();
    }

    @Test
    void startSessionSwitchesToVoting() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).votingStarted(false).build()));
        when(sessionRepository.save(any(Session.class))).thenAnswer(i -> i.getArgument(0));

        Session started = sessionService.startSession(sessionId).orElseThrow();

        assertThat(started.getVotingStarted()).isTrue();
        assertThat(started.getStatus()).isEqualTo(SessionStatus.VOTING);
    }

    @Test
    void finishSessionStopsVoting() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).votingStarted(true).build()));
        when(sessionRepository.save(any(Session.class))).thenAnswer(i -> i.getArgument(0));

        Session finished = sessionService.finishSession(sessionId).orElseThrow();

        assertThat(finished.getVotingStarted()).isFalse();
        assertThat(finished.getStatus()).isEqualTo(SessionStatus.FINISHED);
    }

    @Test
    void isNameTakenComparesCaseInsensitively() {
        UUID sessionId = UUID.randomUUID();
        when(participantRepository.existsBySessionIdAndNameIgnoreCase(sessionId, "al ICE")).thenReturn(true);

        assertThat(sessionService.isNameTaken(sessionId, "  al   ICE  ")).isTrue();
    }
}
