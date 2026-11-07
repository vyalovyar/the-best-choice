package com.moviechoice.voting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moviechoice.voting.service.VotingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class VotingControllerParticipantsTest {

    @Mock
    private VotingService votingService;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    void participantLeftNotifiesLeftTopicWithName() {
        UUID sessionId = UUID.randomUUID();
        VotingController controller = new VotingController(votingService, messagingTemplate, new ObjectMapper());

        controller.participantLeft(sessionId, java.util.Map.of(
                "participantId", "p-1", "participantName", "Alice"));

        verify(messagingTemplate).convertAndSend(
                eq("/topic/session/" + sessionId + "/left"),
                eq(java.util.Map.of("participantId", "p-1", "participantName", "Alice")));
    }

    @Test
    void participantsChangedNotifiesSessionTopic() {
        UUID sessionId = UUID.randomUUID();
        VotingController controller = new VotingController(votingService, messagingTemplate, new ObjectMapper());

        controller.participantsChanged(sessionId);

        verify(messagingTemplate).convertAndSend(eq("/topic/session/" + sessionId + "/participants"), any(Object.class));
    }
}
