package com.moviechoice.session.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviechoice.session.entity.Session;
import com.moviechoice.session.entity.SessionStatus;
import com.moviechoice.session.entity.Participant;
import com.moviechoice.session.repository.SessionRepository;
import com.moviechoice.session.repository.ParticipantRepository;

@Service
public class SessionServiceImpl implements SessionService {

    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;
    private static final int CODE_ATTEMPTS = 12;
    private static final int TOKEN_BYTES = 24;

    private final SecureRandom random = new SecureRandom();

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private SessionEventNotifier sessionEventNotifier;

    private final Duration staleTimeout;

    public SessionServiceImpl(SessionRepository sessionRepository,
                              ParticipantRepository participantRepository,
                              @Value("${session.participant.stale-timeout:25s}") Duration staleTimeout) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.staleTimeout = staleTimeout;
    }

    @Autowired(required = false)
    public void setSessionEventNotifier(SessionEventNotifier sessionEventNotifier) {
        this.sessionEventNotifier = sessionEventNotifier;
    }

    @Override
    @Transactional
    public Session createSession() {
        Session session = Session.builder()
                .code(generateUniqCode())
                .status(SessionStatus.ACTIVE)
                .createdAt(ZonedDateTime.now())
                .currentMovieIndex(0)
                .votingStarted(false)
                .hostToken(randomToken())
                .build();
        session.setCreatedAt(ZonedDateTime.now());

        return sessionRepository.save(session);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Session> findByCode(String code) {
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

    public String generateUniqCode() {
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!sessionRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Не удалось сгенерировать уникальный код сессии");
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        return builder.toString();
    }

    private String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    @Transactional
    public Participant addParticipant(UUID sessionId, String participantName, boolean host) {
        return getSessionById(sessionId).map(session -> {
            String normalizedName = normalizeParticipantName(participantName);
            ZonedDateTime now = ZonedDateTime.now();
            Participant participant = Participant.builder()
                    .session(session)
                    .name(normalizedName)
                    .joinedAt(now)
                    .lastSeen(now)
                    .host(host)
                    .token(randomToken())
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
    @Transactional(readOnly = true)
    public Optional<Participant> findByToken(UUID sessionId, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return participantRepository.findAllBySessionId(sessionId).stream()
                .filter(p -> token.equals(p.getToken()))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHost(UUID sessionId, UUID participantId) {
        if (participantId == null) {
            return false;
        }
        return participantRepository.findById(participantId)
                .filter(p -> p.getSession() != null && sessionId.equals(p.getSession().getId()))
                .map(Participant::isHost)
                .orElse(false);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isHostToken(UUID sessionId, String hostToken) {
        if (hostToken == null || hostToken.isBlank()) {
            return false;
        }
        return sessionRepository.findById(sessionId)
                .map(session -> hostToken.equals(session.getHostToken()))
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean heartbeat(UUID sessionId, UUID participantId) {
        if (participantId == null) {
            return false;
        }
        boolean belongs = participantRepository.findById(participantId)
                .filter(p -> p.getSession() != null && sessionId.equals(p.getSession().getId()))
                .isPresent();
        if (!belongs) {
            return false;
        }
        participantRepository.touch(participantId, ZonedDateTime.now());
        return true;
    }

    @Override
    @Transactional
    public Optional<Participant> removeParticipant(UUID participantId) {
        return removeParticipant(participantId, "LEFT");
    }

    @Override
    @Transactional
    public Optional<Participant> removeParticipant(UUID participantId, String reason) {
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
                sessionEventNotifier.participantRemoved(sessionId, participantId.toString(), participantName,
                        reason);
            }
        }
        return participant;
    }

    @Override
    @Transactional
    public int pruneStaleParticipants() {
        ZonedDateTime threshold = ZonedDateTime.now().minus(staleTimeout);
        List<Participant> stale = participantRepository.findStale(threshold);
        int removed = 0;
        for (Participant participant : stale) {
            UUID participantId = participant.getId();
            UUID sessionId = participant.getSession() == null ? null : participant.getSession().getId();
            String participantName = participant.getName();
            participantRepository.deleteById(participantId);
            removed++;
            if (sessionId != null) {
                notifyParticipantsChanged(sessionId);
                if (sessionEventNotifier != null) {
                    sessionEventNotifier.participantRemoved(sessionId, participantId.toString(),
                            participantName, "STALE");
                }
            }
        }
        return removed;
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
