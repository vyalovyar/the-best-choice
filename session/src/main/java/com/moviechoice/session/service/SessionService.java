package com.moviechoice.session.service;

import com.moviechoice.session.entity.Session;

import java.util.Optional;
import java.util.UUID;

public interface SessionService {
    public Session createSession();
    public Optional<Session> findByCode(String code);
    public Optional<Session> getSessionById(UUID sessionId);
}
