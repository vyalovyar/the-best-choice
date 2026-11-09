package com.moviechoice.session.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class SessionEventNotifier {

    private static final Logger log = LoggerFactory.getLogger(SessionEventNotifier.class);
    private static final String PARTICIPANTS_CHANGED_PATH = "/api/voting/sessions/%s/participants-changed";
    private static final String PARTICIPANT_LEFT_PATH = "/api/voting/sessions/%s/participant-removed";

    private final RestTemplate restTemplate = new RestTemplate();
    private final String votingServiceUrl;

    public SessionEventNotifier(@Value("${voting.service.url:}") String votingServiceUrl) {
        this.votingServiceUrl = votingServiceUrl == null ? "" : votingServiceUrl.trim();
    }

    public void participantsChanged(UUID sessionId) {
        post(PARTICIPANTS_CHANGED_PATH, sessionId, null);
    }

    public void participantRemoved(UUID sessionId, String participantId, String participantName, String reason) {
        post(PARTICIPANT_LEFT_PATH, sessionId, Map.of(
                "participantId", participantId == null ? "" : participantId,
                "participantName", participantName == null ? "" : participantName,
                "reason", reason == null ? "LEFT" : reason));
    }

    private void post(String path, UUID sessionId, Map<String, String> body) {
        if (votingServiceUrl.isBlank() || sessionId == null) {
            return;
        }
        String url = votingServiceUrl + String.format(path, sessionId);
        Map<String, String> payload = body == null ? Map.of() : body;
        Thread thread = new Thread(() -> {
            try {
                restTemplate.postForObject(url, payload, Void.class);
            } catch (Exception e) {
                log.debug("Не удалось уведомить voting-сервис: {}", e.getMessage());
            }
        }, "session-notify-" + sessionId);
        thread.setDaemon(true);
        thread.start();
    }
}
