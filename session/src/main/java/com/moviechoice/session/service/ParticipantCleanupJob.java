package com.moviechoice.session.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.moviechoice.session.security.RateLimiter;

@Component
public class ParticipantCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ParticipantCleanupJob.class);

    private final SessionService sessionService;
    private final RateLimiter rateLimiter;

    public ParticipantCleanupJob(SessionService sessionService, RateLimiter rateLimiter) {
        this.sessionService = sessionService;
        this.rateLimiter = rateLimiter;
    }

    @Scheduled(fixedDelayString = "${session.participant.cleanup-interval-ms:10000}")
    public void removeStaleParticipants() {
        try {
            int removed = sessionService.pruneStaleParticipants();
            if (removed > 0) {
                log.info("Удалено неактивных участников: {}", removed);
            }
        } catch (Exception e) {
            log.warn("Не удалось удалить неактивных участников: {}", e.getMessage());
        }
        rateLimiter.cleanUp();
    }
}
