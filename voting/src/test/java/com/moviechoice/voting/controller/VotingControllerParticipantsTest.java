package com.moviechoice.voting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import java.util.Map;
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
    void participantRemovedNotifiesLeftTopicWithReason() {
        UUID sessionId = UUID.randomUUID();
        VotingController controller = new VotingController(votingService, messagingTemplate, new ObjectMapper());

        controller.participantRemoved(sessionId, Map.of(
                "participantId", "p-1", "participantName", "Alice", "reason", "KICKED"));

        verify(messagingTemplate).convertAndSend(
                eq("/topic/session/" + sessionId + "/left"),
                eq(Map.of("participantId", "p-1", "participantName", "Alice", "reason", "KICKED")));
    }

    @Test
    void participantRemovedDefaultsToLeftReasonWhenBodyMissing() {
        UUID sessionId = UUID.randomUUID();
        VotingController controller = new VotingController(votingService, messagingTemplate, new ObjectMapper());

        controller.participantRemoved(sessionId, null);

        verify(messagingTemplate).convertAndSend(
                eq("/topic/session/" + sessionId + "/left"),
                eq(Map.of("participantId", "", "participantName", "", "reason", "LEFT")));
    }

    @Test
    void participantsChangedNotifiesSessionTopic() {
        UUID sessionId = UUID.randomUUID();
        VotingController controller = new VotingController(votingService, messagingTemplate, new ObjectMapper());

        controller.participantsChanged(sessionId);

        verify(messagingTemplate).convertAndSend(eq("/topic/session/" + sessionId + "/participants"), any(Object.class));
    }
}
