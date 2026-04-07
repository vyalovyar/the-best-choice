package com.moviechoice.session.service;


import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

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

    public SessionServiceImpl(SessionRepository sessionRepository, ParticipantRepository participantRepository) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
    }

    //Метод для создания сессии
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
    public Optional<Session> findByCode(String code){
        return sessionRepository.findByCode(code);
    }
    @Override
    public Optional<Session> getSessionById(UUID sessionId) {
        return sessionRepository.findById(sessionId);
    }

    @Override
    public Session saveSession(Session session) {
        return sessionRepository.save(session);
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
            return participantRepository.save(participant);
        }).orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Participant> getParticipants(UUID sessionId) {
        return participantRepository.findAllBySessionId(sessionId);
    }

    @Override
    public void removeParticipant(UUID participantId) {
        participantRepository.deleteById(participantId);
    }

    private String normalizeParticipantName(String participantName) {
        if (participantName == null) {
            return "Guest";
        }

        String normalizedName = participantName.trim();
        return normalizedName.isEmpty() ? "Guest" : normalizedName;
    }
}
