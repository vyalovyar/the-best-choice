package com.moviechoice.gateway.warmup;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StartupWarmupListenerTest {

    @Mock
    private UpstreamWarmupService upstreamWarmupService;

    @InjectMocks
    private StartupWarmupListener startupWarmupListener;

    @Test
    void onApplicationReadyStartsWarmupWhenFeatureIsEnabled() {
        WarmupProperties properties = new WarmupProperties();
        properties.setEnabled(true);
        properties.setStartupEnabled(true);
        startupWarmupListener = new StartupWarmupListener(properties, upstreamWarmupService);

        startupWarmupListener.onApplicationReady();

        verify(upstreamWarmupService).warmupAsync();
    }

    @Test
    void onApplicationReadySkipsWarmupWhenStartupWarmupIsDisabled() {
        WarmupProperties properties = new WarmupProperties();
        properties.setEnabled(true);
        properties.setStartupEnabled(false);
        startupWarmupListener = new StartupWarmupListener(properties, upstreamWarmupService);

        startupWarmupListener.onApplicationReady();

        verify(upstreamWarmupService, never()).warmupAsync();
    }
}
