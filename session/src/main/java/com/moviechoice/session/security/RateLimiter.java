package com.moviechoice.session.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {

    private static final class Window {
        long startedAt;
        int count;
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final long windowMillis;

    public RateLimiter(@Value("${session.rate-limit.max-requests:20}") int maxRequests,
                       @Value("${session.rate-limit.window:60s}") Duration window) {
        this.maxRequests = maxRequests;
        this.windowMillis = window.toMillis();
    }

    public boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> {
            Window created = new Window();
            created.startedAt = now;
            return created;
        });

        synchronized (window) {
            if (now - window.startedAt >= windowMillis) {
                window.startedAt = now;
                window.count = 0;
            }
            return ++window.count <= maxRequests;
        }
    }

    public void cleanUp() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt >= windowMillis * 2);
    }
}
