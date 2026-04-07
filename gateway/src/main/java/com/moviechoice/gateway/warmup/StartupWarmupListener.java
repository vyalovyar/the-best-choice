package com.moviechoice.gateway.warmup;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupWarmupListener {

    private final WarmupProperties properties;
    private final UpstreamWarmupService upstreamWarmupService;

    public StartupWarmupListener(WarmupProperties properties, UpstreamWarmupService upstreamWarmupService) {
        this.properties = properties;
        this.upstreamWarmupService = upstreamWarmupService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!properties.isEnabled() || !properties.isStartupEnabled()) {
            return;
        }

        upstreamWarmupService.warmupAsync();
    }
}
