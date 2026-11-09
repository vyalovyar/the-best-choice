package com.moviechoice.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.security.RateLimiter;
import com.moviechoice.session.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock
    private SessionService sessionService;

    @Mock
    private HttpServletRequest request;

    private SessionController sessionController;

    @BeforeEach
    void setUp() {
        RateLimiter rateLimiter = new RateLimiter(1000, Duration.ofMinutes(1));
        sessionController = new SessionController(sessionService, rateLimiter);
    }

    private void hostSession() {
        lenient().when(sessionService.isHostToken(any(), any())).thenReturn(false);
    }

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

        var response = sessionController.getParticipants(sessionId.toString(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("currentMovieIndex", 4)
                .containsEntry("votingStarted", true)
                .containsEntry("sessionId", sessionId.toString())
                .containsEntry("participants", List.of());
        verify(sessionService).getParticipants(sessionId);
    }

    @Test
    void getParticipantsRefreshesHeartbeatOfCaller() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).status(SessionStatus.ACTIVE).build()));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of(participant("Alice")));

        var response = sessionController.getParticipants(sessionId.toString(), participantId.toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionService).heartbeat(sessionId, participantId);
    }

    @Test
    void heartbeatReportsMissingParticipantAsNotFound() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(sessionService.heartbeat(sessionId, participantId)).thenReturn(false);

        var response = sessionController.heartbeat(sessionId.toString(),
                Map.of("participantId", participantId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void heartbeatAcceptsLiveParticipant() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        when(sessionService.heartbeat(sessionId, participantId)).thenReturn(true);

        var response = sessionController.heartbeat(sessionId.toString(),
                Map.of("participantId", participantId.toString()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("alive", true);
    }

    @Test
    void startSessionRejectsFewerThanTwoParticipants() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.isHostToken(sessionId, "host-tok")).thenReturn(true);
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of(participant("Alice")));

        var response = sessionController.startSession(sessionId.toString(), null, "host-tok");

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
        when(sessionService.isHostToken(sessionId, "host-tok")).thenReturn(true);
        when(sessionService.startSession(sessionId)).thenReturn(Optional.of(started));

        var response = sessionController.startSession(sessionId.toString(), null, "host-tok");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("votingStarted", true);
    }

    @Test
    void startSessionRejectsNonHostRequester() {
        UUID sessionId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        when(sessionService.isHost(sessionId, requesterId)).thenReturn(false);

        var response = sessionController.startSession(sessionId.toString(), requesterId.toString(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "NOT_HOST");
        verify(sessionService, never()).startSession(any());
    }

    @Test
    void finishSessionStopsVotingAndMarksSessionFinished() {
        UUID sessionId = UUID.randomUUID();
        Session finished = Session.builder()
                .id(sessionId).code("ABC123").status(SessionStatus.FINISHED).votingStarted(false).build();
        when(sessionService.finishSession(sessionId)).thenReturn(Optional.of(finished));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of());

        /* finish вызывает voting-сервис по совпадению, с внутренним токеном */
        var response = sessionController.finishSession(
                sessionId.toString(), null, null, "change-me-internal");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "FINISHED")
                .containsEntry("votingStarted", false);
    }

    @Test
    void finishSessionRejectsExternalCallerWithoutHostRights() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.isHostToken(any(), any())).thenReturn(false);

        var response = sessionController.finishSession(sessionId.toString(), null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sessionService, never()).finishSession(any());
    }

    @Test
    void startSessionRejectsExternalCallerWithoutHostRights() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.isHostToken(any(), any())).thenReturn(false);

        var response = sessionController.startSession(sessionId.toString(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sessionService, never()).startSession(any());
    }

    @Test
    void updateMovieIndexRejectsExternalCaller() {
        UUID sessionId = UUID.randomUUID();

        var response = sessionController.updateSessionMovieIndex(
                sessionId.toString(), Map.of("currentMovieIndex", 5), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(sessionService, never()).updateMovieIndex(any(), eq(5));
    }

    @Test
    void leaveSessionRemovesParticipantAndReturnsRemaining() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        Participant participant = Participant.builder().id(participantId).session(session).name("Alice").build();
        Participant bob = participant("Bob");

        when(sessionService.findByParticipantId(participantId)).thenReturn(Optional.of(participant));
        when(sessionService.removeParticipant(participantId)).thenReturn(Optional.of(participant));
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
    void kickParticipantRemovesMemberForHost() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Session session = Session.builder().id(sessionId).build();
        Participant target = Participant.builder().id(targetId).session(session).name("Bob").build();

        when(sessionService.isHost(sessionId, hostId)).thenReturn(true);
        when(sessionService.findByParticipantId(targetId)).thenReturn(Optional.of(target));
        when(sessionService.removeParticipant(targetId, "KICKED")).thenReturn(Optional.of(target));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of());

        var response = sessionController.kickParticipant(sessionId.toString(), targetId.toString(),
                hostId.toString(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("participantId", targetId.toString());
        verify(sessionService).removeParticipant(targetId, "KICKED");
    }

    @Test
    void kickParticipantRejectsNonHost() {
        UUID sessionId = UUID.randomUUID();
        UUID requesterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();

        when(sessionService.isHost(sessionId, requesterId)).thenReturn(false);

        var response = sessionController.kickParticipant(sessionId.toString(), targetId.toString(),
                requesterId.toString(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "NOT_HOST");
        verify(sessionService, never()).removeParticipant(any(UUID.class));
    }

    @Test
    void kickParticipantRejectsHostKickingItself() {
        UUID sessionId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();

        when(sessionService.isHost(sessionId, hostId)).thenReturn(true);

        var response = sessionController.kickParticipant(sessionId.toString(), hostId.toString(),
                hostId.toString(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("code", "SELF_KICK");
    }

    @Test
    void nameAvailableReportsFreeNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).status(SessionStatus.ACTIVE).build()));
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(false);

        var response = sessionController.isNameAvailable(sessionId.toString(), " Alice ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("available", true).containsEntry("name", "Alice");
    }

    @Test
    void nameAvailableReportsTakenNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).status(SessionStatus.ACTIVE).build()));
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(true);

        var response = sessionController.isNameAvailable(sessionId.toString(), "Alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("available", false);
    }

    @Test
    void nameAvailableRejectsEmptyNickname() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId))
                .thenReturn(Optional.of(Session.builder().id(sessionId).status(SessionStatus.ACTIVE).build()));

        var response = sessionController.isNameAvailable(sessionId.toString(), "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void nameAvailableRefusesNicknameOnceVotingStarted() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(Session.builder()
                .id(sessionId).status(SessionStatus.VOTING).votingStarted(true).build()));

        var response = sessionController.isNameAvailable(sessionId.toString(), "Alice");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "VOTING_STARTED");
    }

    @Test
    void joinSessionRejectsTakenNicknameWithConflict() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(Session.builder()
                .id(sessionId).status(SessionStatus.ACTIVE).build()));
        when(sessionService.isHostToken(eq(sessionId), any())).thenReturn(false);
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(true);

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Alice"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("code", "NAME_TAKEN");
        verify(sessionService, never()).addParticipant(any(UUID.class), any(), anyBoolean());
    }

    @Test
    void joinSessionRejectsBlankNickname() {
        UUID sessionId = UUID.randomUUID();

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "  "), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void joinSessionIsLockedOnceVotingStarted() {
        UUID sessionId = UUID.randomUUID();
        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(Session.builder()
                .id(sessionId).status(SessionStatus.VOTING).votingStarted(true).build()));
        when(sessionService.findByToken(eq(sessionId), any())).thenReturn(Optional.empty());

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Alice"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "VOTING_STARTED");
        verify(sessionService, never()).addParticipant(any(UUID.class), any(), anyBoolean());
    }

    @Test
    void joinSessionLetsOwnParticipantReturnWithToken() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId).code("ABCD2345").status(SessionStatus.VOTING).votingStarted(true).build();
        Participant existing = Participant.builder()
                .id(participantId).session(session).name("Alice").token("token-1").host(true).build();

        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.findByToken(sessionId, "token-1")).thenReturn(Optional.of(existing));
        when(sessionService.heartbeat(sessionId, participantId)).thenReturn(true);

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Alice", "token", "token-1"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("participantId", participantId.toString())
                .containsEntry("token", "token-1")
                .containsEntry("host", true);
        verify(sessionService, never()).addParticipant(any(UUID.class), any(), anyBoolean());
    }

    @Test
    void joinSessionReturnsTokenForFreshParticipant() {
        UUID sessionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId).code("ABCD2345").status(SessionStatus.ACTIVE).votingStarted(false).build();
        Participant created = Participant.builder()
                .id(participantId).session(session).name("Alice").token("fresh-token").host(false).build();

        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isHostToken(eq(sessionId), any())).thenReturn(false);
        when(sessionService.isNameTaken(sessionId, "Alice")).thenReturn(false);
        when(sessionService.addParticipant(sessionId, "Alice", false)).thenReturn(created);

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Alice"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("participantId", participantId.toString())
                .containsEntry("token", "fresh-token")
                .containsEntry("code", "ABCD2345");
    }

    @Test
    void joinSessionIgnoresSpoofedHostFlagWithoutHostToken() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId).code("ABCD2345").status(SessionStatus.ACTIVE).votingStarted(false).build();
        Participant created = Participant.builder()
                .id(UUID.randomUUID()).name("Самозванец").host(false).token("t").build();

        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isHostToken(sessionId, null)).thenReturn(false);
        when(sessionService.isNameTaken(sessionId, "Самозванец")).thenReturn(false);
        when(sessionService.addParticipant(sessionId, "Самозванец", false)).thenReturn(created);

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Самозванец", "host", "true"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("host", false);
        verify(sessionService).addParticipant(sessionId, "Самозванец", false);
    }

    @Test
    void joinSessionGrantsHostForValidHostToken() {
        UUID sessionId = UUID.randomUUID();
        Session session = Session.builder()
                .id(sessionId).code("ABCD2345").status(SessionStatus.ACTIVE).votingStarted(false).build();
        Participant created = Participant.builder()
                .id(UUID.randomUUID()).name("Хозяин").host(true).token("t2").build();

        when(sessionService.getSessionById(sessionId)).thenReturn(Optional.of(session));
        when(sessionService.isHostToken(sessionId, "host-secret")).thenReturn(true);
        when(sessionService.isNameTaken(sessionId, "Хозяин")).thenReturn(false);
        when(sessionService.addParticipant(sessionId, "Хозяин", true)).thenReturn(created);

        var response = sessionController.joinSession(sessionId.toString(),
                Map.of("name", "Хозяин", "hostToken", "host-secret"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("host", true);
        verify(sessionService).addParticipant(sessionId, "Хозяин", true);
    }

    @Test
    void kickParticipantAcceptsHostTokenWithoutRequester() {
        UUID sessionId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        Participant target = Participant.builder()
                .id(targetId).name("Bob").session(Session.builder().id(sessionId).build()).build();

        when(sessionService.isHost(eq(sessionId), any())).thenReturn(false);
        when(sessionService.isHostToken(sessionId, "host-secret")).thenReturn(true);
        when(sessionService.findByParticipantId(targetId)).thenReturn(Optional.of(target));
        when(sessionService.getParticipants(sessionId)).thenReturn(List.of());

        var response = sessionController.kickParticipant(sessionId.toString(), targetId.toString(),
                null, "host-secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionService).removeParticipant(targetId, "KICKED");
    }

    @Test
    void createSessionIsRateLimitedPerClient() {
        RateLimiter strict = new RateLimiter(1, Duration.ofMinutes(1));
        SessionController controller = new SessionController(sessionService, strict);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");
        when(sessionService.createSession()).thenReturn(Session.builder()
                .id(UUID.randomUUID()).code("ABCD2345").status(SessionStatus.ACTIVE).build());

        var first = controller.createSession(request);
        var second = controller.createSession(request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(second.getBody()).containsEntry("code", "RATE_LIMITED");
        verify(sessionService).createSession();
    }

    private static Participant participant(String name) {
        return Participant.builder().id(UUID.randomUUID()).name(name).build();
    }
}
