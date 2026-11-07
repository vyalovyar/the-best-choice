package com.moviechoice.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.service.SessionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionService sessionService;

    @InjectMocks
    private SessionController sessionController;

    @Test
    void getParticipantsIncludesCurrentMovieIndexForClientRecovery() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId)
                .status(SessionStatus.ACTIVE)
                .currentMovieIndex(4)
                .votingStarted(true)
                .build();
        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of());

        var response = sessionController.getParticipants(sessionId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("currentMovieIndex", 4)
                .containsEntry("votingStarted", true)
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("participants", List.of());
        verify(sessionService).getParticipants(sessionId);
    }

    @Test
    void startSessionRejectsFewerThanTwoParticipants() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of(participant("Alice")));

        var response = sessionController.startSession(sessionId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsKey("error");
        verify(sessionService, never()).startSession(any());
    }

    @Test
    public void startSessionStartsWhenTwoParticipantsJoined() {
        UUID sessionId = UUID.randomUUID();
        Session started = Session.builder()
                .id(sessionId).code("ABC123").status(SessionStatus.VOTING).votingStarted(true).build();
        when(sessionService.getParticipants(sessionId))
                .thenReturn(List.of(participant("Alice"), participant("Bob")));
        when(sessionService.startSession(sessionId)).thenReturn(Optional.of(started));

        var response = sessionController.startSession(sessionId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("votingStarted", true);
    }

    @Test
    void finishSessionStopsVotingAndMarksSessionFinished() {
        UUID sessionId = UUID.randomUUID();
        Session finished = Session.builder()
                .id(sessionId).code("ABC123").status(SessionStatus.FINISHED).votingStarted(false).build();
        when(sessionService.finishSession(sessionId)).thenReturn(Optional.of(finished));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of());

        var response = sessionController.finishSession(sessionId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "FINISHED")
                .containsEntry("votingStarted", false);
    }

    @Test
    void leaveSessionRemovesParticipantAndReturnsRemaining() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        Participant alice = Participant.builder().id(participantId).session(session).name("Alice").build();
        Participant bob = participant("Bob");

        when(sessionService.findByParticipantId(participantId)).thenReturn(Optional.of(alice));
        when(sessionService.removeParticipant(participantId)).thenReturn(Optional.of(alice));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of(bob));

        var response = sessionController.leaveSession(sessionId.toString(), participantId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("participantId", participantId.toString());
        assertThat((List<?>) response.getBody().get("participants")).hasSize(1);
        verify(sessionService).removeParticipant(participantId);
    }

    @Test
    void leaveSessionRejectsParticipantFromAnotherSession() {
        UUID sessionId = UUID.randomUUID();
        UUID otherSessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Participant foreign = Participant.builder()
                .id(participantId)
                .session(Session.builder().id(otherSessionId).build())
                .name("Alice")
                .build();

        when(sessionService.findByParticipantId(participantId)).thenReturn(Optional.of(foreign));

        var response = sessionController.leaveSession(sessionId.toString(), participantId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(sessionService, never()).removeParticipant(any(UUID.class));
    }

    @Test
    void nameAvailableReportsFreeNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(false);

        var response = sessionController.isNameAvailable(sessionId.toString(), " Alice ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("available", true).containsEntry("name", "Alice");
    }

    @Test
    void nameAvailableReportsTakenNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(true);

        var response = sessionController.isNameAvailable(sessionId.toString(), "Alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("available", false);
    }

    @Test
    void nameAvailableRejectsEmptyNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).build()));

        var response = sessionController.isNameAvailable(sessionId.toString(), "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void joinSessionRejectsTakenNicknameWithConflict() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(true);

        var response = sessionController.joinSession(sessionId.toString(), Map.of("name", "Alice"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "NAME_TAKEN");
        verify(sessionService, never()).addParticipant(any(UUID.class), any());
    }

    @Test
    void joinSessionRejectsBlankNickname() {
        UUID sessionId = UUID.randomUUID();

        var response = sessionController.joinSession(sessionId.toString(), Map.of("name", "  "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static Participant participant(String name) {
        return Participant.builder().id(UUID.randomUUID()).name(name).build();
    }
}
