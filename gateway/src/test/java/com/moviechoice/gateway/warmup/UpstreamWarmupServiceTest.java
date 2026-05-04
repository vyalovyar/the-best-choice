package com.moviechoice.gateway.warmup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpstreamWarmupServiceTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<Void> httpResponse;

    @Test
    void warmupAsyncReturnsReadyImmediatelyWhenDisabled() {
        WarmupProperties properties = warmupProperties(false, Duration.ofSeconds(1), Duration.ZERO);
        UpstreamWarmupService service = new UpstreamWarmupService(properties, "http://session-service", "http://voting-service", httpClient);

        WarmupResult result = service.warmupAsync().join();

        assertThat(result.ready()).isTrue();
        assertThat(result.services()).isEmpty();
    }

    @Test
    void warmupAsyncSkipsWhenNoUrlsConfigured() {
        WarmupProperties properties = warmupProperties(true, Duration.ofSeconds(1), Duration.ZERO);
        UpstreamWarmupService service = new UpstreamWarmupService(properties, "", "", httpClient);

        WarmupResult result = service.warmupAsync().join();

        assertThat(result.ready()).isTrue();
        assertThat(result.services()).isEmpty();
    }

    @Test
    void warmupAsyncMarksServiceReadyWhenHealthEndpointReturnsSuccess() throws Exception {
        WarmupProperties properties = warmupProperties(true, Duration.ofSeconds(1), Duration.ZERO);
        UpstreamWarmupService service = new UpstreamWarmupService(properties, "http://session-service:8081/", "", httpClient);

        when(httpResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);

        WarmupResult result = service.warmupAsync().join();

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        assertThat(requestCaptor.getValue().uri().toString()).isEqualTo("http://session-service:8081/actuator/health");
        assertThat(result.ready()).isTrue();
        assertThat(result.services()).singleElement().satisfies(status -> {
            assertThat(status.name()).isEqualTo("session");
            assertThat(status.url()).isEqualTo("http://session-service:8081");
            assertThat(status.ready()).isTrue();
            assertThat(status.attempts()).isEqualTo(1);
            assertThat(status.message()).isEqualTo("HTTP 200");
        });
    }

    @Test
    void warmupAsyncReturnsFailureForInvalidUrl() {
        WarmupProperties properties = warmupProperties(true, Duration.ofSeconds(1), Duration.ZERO);
        UpstreamWarmupService service = new UpstreamWarmupService(properties, "://broken-url", "", httpClient);

        WarmupResult result = service.warmupAsync().join();

        assertThat(result.ready()).isFalse();
        assertThat(result.services()).singleElement().satisfies(status -> {
            assertThat(status.name()).isEqualTo("session");
            assertThat(status.ready()).isFalse();
            assertThat(status.attempts()).isEqualTo(1);
            assertThat(status.message()).isEqualTo("Invalid URL");
        });
    }

    private static WarmupProperties warmupProperties(boolean enabled, Duration timeout, Duration retryDelay) {
        WarmupProperties properties = new WarmupProperties();
        properties.setEnabled(enabled);
        properties.setStartupEnabled(true);
        properties.setTimeout(timeout);
        properties.setRetryDelay(retryDelay);
        return properties;
    }
}
