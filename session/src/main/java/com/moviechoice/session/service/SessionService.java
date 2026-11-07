package com.moviechoice.session.service;

import java.util.Optional;
import java.util.UUID;
import java.util.List;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.Participant;

public interface SessionService {
    Session createSession();
    Optional<Session> findByCode(String code);
    Optional<Session> getSessionById(UUID sessionId);
    Session saveSession(Session session);
    Optional<Session> updateMovieIndex(UUID sessionId, int movieIndex);
    Optional<Session> startSession(UUID sessionId);
    Optional<Session> finishSession(UUID sessionId);
    Participant addParticipant(UUID sessionId, String participantName);
    List<Participant> getParticipants(UUID sessionId);
    Optional<Participant> findByParticipantId(UUID participantId);
    Optional<Participant> removeParticipant(UUID participantId);
    boolean isNameTaken(UUID sessionId, String participantName);
}
