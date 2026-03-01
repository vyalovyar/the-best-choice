package com.moviechoice.session.service;

import java.util.Optional;
import java.util.UUID;

import com.moviechoice.session.entity.Session;

public interface SessionService {
    public Session createSession();
    public Optional<Session> findByCode(String code);
    public Optional<Session> getSessionById(UUID sessionId);
    public Session saveSession(Session session);
}
