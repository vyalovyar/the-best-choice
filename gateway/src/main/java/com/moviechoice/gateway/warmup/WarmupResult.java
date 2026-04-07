package com.moviechoice.gateway.warmup;

import java.time.Instant;
import java.util.List;

public record WarmupResult(
        boolean ready,
        Instant startedAt,
        Instant finishedAt,
        List<ServiceWarmupStatus> services
) {
}
