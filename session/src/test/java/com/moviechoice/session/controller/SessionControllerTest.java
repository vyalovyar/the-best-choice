package com.moviechoice.session.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
}
