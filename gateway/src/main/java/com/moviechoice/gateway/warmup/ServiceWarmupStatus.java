package com.moviechoice.gateway.warmup;

public record ServiceWarmupStatus(
        String name,
        String url,
        boolean ready,
        int attempts,
        String message
) {
}
