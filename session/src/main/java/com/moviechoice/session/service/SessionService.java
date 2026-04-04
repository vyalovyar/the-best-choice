package com.moviechoice.session.service;

import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.Map;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.Participant;

public interface SessionService {
    public Session createSession();
    public Optional<Session> findByCode(String code);
    public Optional<Session> getSessionById(UUID sessionId);
    public Session saveSession(Session session);
    public Participant addParticipant(UUID sessionId, String participantName);
    public List<Participant> getParticipants(UUID sessionId);
    public void removeParticipant(UUID participantId);
}
