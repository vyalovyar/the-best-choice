package com.moviechoice.session.service;


import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.repository.SessionRepository;
import com.moviechoice.session.repository.ParticipantRepository;

@Service
public class SessionServiceImpl implements SessionService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private SessionEventNotifier sessionEventNotifier;

    public SessionServiceImpl(SessionRepository sessionRepository, ParticipantRepository participantRepository) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
    }

    @Autowired(required = false)
    public void setSessionEventNotifier(SessionEventNotifier sessionEventNotifier) {
        this.sessionEventNotifier = sessionEventNotifier;
    }

    //Метод для создания сессии
    @Override
    @Transactional
    public Session createSession(){
        //генерирую уникальный код с помощью какого-нибудь алгоритма
        String uniqueCode = generateUniqCode();
        Session session = Session.builder()
                .code(uniqueCode)
                .status(SessionStatus.ACTIVE)
                .createdAt(ZonedDateTime.now())
                .currentMovieIndex(0)
                .votingStarted(false)
                .build();
        session.setCreatedAt(ZonedDateTime.now());

        return sessionRepository.save(session);
    }

    //метод для поиска сессии по коду
    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findByCode(String code){
        return sessionRepository.findByCode(code);
    }
    @Override
    @Transactional(readOnly = true)
    public Optional<Session> getSessionById(UUID sessionId) {
        return sessionRepository.findById(sessionId);
    }

    @Override
    @Transactional
    public Session saveSession(Session session) {
        session.setUpdatedAt(ZonedDateTime.now());
        return sessionRepository.save(session);
    }

    @Override
    @Transactional
    public Optional<Session> updateMovieIndex(UUID sessionId, int movieIndex) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setCurrentMovieIndex(movieIndex);
            session.setUpdatedAt(ZonedDateTime.now());
            return sessionRepository.save(session);
        });
    }

    @Override
    @Transactional
    public Optional<Session> startSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setVotingStarted(true);
            session.setStatus(SessionStatus.VOTING);
            session.setUpdatedAt(ZonedDateTime.now());
            return sessionRepository.save(session);
        });
    }

    @Override
    @Transactional
    public Optional<Session> finishSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setVotingStarted(false);
            session.setStatus(SessionStatus.FINISHED);
            session.setUpdatedAt(ZonedDateTime.now());
            return sessionRepository.save(session);
        });
    }

    //Генерерация случайного кода 
    public String generateUniqCode(){
        // Генерируем код с использованием UUID для гарантированной уникальности и скорости
        String uuid = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return uuid;
    }

    @Override
    @Transactional
    public Participant addParticipant(UUID sessionId, String participantName) {
        return getSessionById(sessionId).map(session -> {
            String normalizedName = normalizeParticipantName(participantName);
            Participant participant = Participant.builder()
                    .session(session)
                    .name(normalizedName)
                    .joinedAt(ZonedDateTime.now())
                    .build();
            Participant saved = participantRepository.save(participant);
            notifyParticipantsChanged(sessionId);
            return saved;
        }).orElse(null);
    }

    private void notifyParticipantsChanged(UUID sessionId) {
        if (sessionEventNotifier != null) {
            sessionEventNotifier.participantsChanged(sessionId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Participant> getParticipants(UUID sessionId) {
        return participantRepository.findAllBySessionId(sessionId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Participant> findByParticipantId(UUID participantId) {
        return participantRepository.findById(participantId);
    }

    @Override
    @Transactional
    public Optional<Participant> removeParticipant(UUID participantId) {
        Optional<Participant> participant = participantRepository.findById(participantId);
        if (participant.isEmpty()) {
            return Optional.empty();
        }
        UUID sessionId = participant.get().getSession() == null
                ? null
                : participant.get().getSession().getId();
        String participantName = participant.get().getName();
        participantRepository.deleteById(participantId);
        if (sessionId != null) {
            notifyParticipantsChanged(sessionId);
            if (sessionEventNotifier != null) {
                sessionEventNotifier.participantLeft(sessionId, participantId.toString(), participantName);
            }
        }
        return participant;
    }

    public boolean isNameTaken(UUID sessionId, String participantName) {
        String normalizedName = normalizeParticipantName(participantName);
        return participantRepository.existsBySessionIdAndNameIgnoreCase(sessionId, normalizedName);
    }

    private String normalizeParticipantName(String participantName) {
        if (participantName == null) return "Guest";
        String name = participantName.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) return "Guest";
        return name.length() > 20 ? name.substring(0, 20) : name;
    }
}
