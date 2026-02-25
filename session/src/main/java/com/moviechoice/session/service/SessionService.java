package com.moviechoice.session.service;

import com.moviechoice.session.entity.Session;

import java.util.Optional;

public interface SessionService {
    public Session createSession();
    public Optional<Session> findByCode(String code);
}
