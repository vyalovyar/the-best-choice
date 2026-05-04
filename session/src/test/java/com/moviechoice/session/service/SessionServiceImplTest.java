package com.moviechoice.session.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.repository.ParticipantRepository;
import com.moviechoice.session.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionServiceImplTest {

    @Mock
    private SessionRepository sessionRepository;

    @Mock
    private ParticipantRepository participantRepository;

    @InjectMocks
    private SessionServiceImpl sessionService;

    @Test
    void createSessionCreatesActiveSessionWithGeneratedCode() {
        when(sessionRepository.save(any(Session.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Session createdSession = sessionService.createSession();

        ArgumentCaptor<Session> sessionCaptor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(sessionCaptor.capture());

        Session savedSession = sessionCaptor.getValue();
        assertThat(createdSession).isSameAs(savedSession);
        assertThat(savedSession.getCode()).matches("[0-9A-F]{6}");
        assertThat(savedSession.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(savedSession.getCreatedAt()).isNotNull();
        assertThat(savedSession.getCurrentMovieIndex()).isZero();
        assertThat(savedSession.getVotingStarted()).isFalse();
    }

    @Test
    void addParticipantTrimsNameAndPersistsParticipant() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Participant participant = sessionService.addParticipant(sessionId, "  Alice  ");

        ArgumentCaptor<Participant> participantCaptor = ArgumentCaptor.forClass(Participant.class);
        verify(participantRepository).save(participantCaptor.capture());

        Participant savedParticipant = participantCaptor.getValue();
        assertThat(participant).isSameAs(savedParticipant);
        assertThat(savedParticipant.getSession()).isSameAs(session);
        assertThat(savedParticipant.getName()).isEqualTo("Alice");
        assertThat(savedParticipant.getJoinedAt()).isNotNull();
    }

    @Test
    void addParticipantUsesGuestWhenNameIsBlank() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(participantRepository.save(any(Participant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Participant participant = sessionService.addParticipant(sessionId, "   ");

        assertThat(participant.getName()).isEqualTo("Guest");
    }

    @Test
    void addParticipantReturnsNullWhenSessionDoesNotExist() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        Participant participant = sessionService.addParticipant(sessionId, "Alice");

        assertThat(participant).isNull();
        verify(participantRepository, never()).save(any(Participant.class));
    }

    @Test
    void getParticipantsReturnsRepositoryResult() {
        UUID sessionId = UUID.randomUUID();
        List<Participant> participants = List.of(
                Participant.builder().name("Alice").build(),
                Participant.builder().name("Bob").build()
        );
        when(participantRepository.findAllBySessionId(sessionId)).thenReturn(participants);

        List<Participant> actualParticipants = sessionService.getParticipants(sessionId);

        assertThat(actualParticipants).isEqualTo(participants);
    }

    @Test
    void removeParticipantDelegatesToRepository() {
        UUID participantId = UUID.randomUUID();

        sessionService.removeParticipant(participantId);

        verify(participantRepository).deleteById(participantId);
    }
}
