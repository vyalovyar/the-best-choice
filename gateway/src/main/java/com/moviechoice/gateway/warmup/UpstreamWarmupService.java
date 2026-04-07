package com.moviechoice.gateway.warmup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class UpstreamWarmupService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamWarmupService.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final WarmupProperties properties;
    private final HttpClient httpClient;
    private final AtomicReference<CompletableFuture<WarmupResult>> inFlightWarmup = new AtomicReference<>();
    private final String sessionServiceUrl;
    private final String votingServiceUrl;

    public UpstreamWarmupService(
            WarmupProperties properties,
            @Value("${SESSION_SERVICE_URL:}") String sessionServiceUrl,
            @Value("${VOTING_SERVICE_URL:}") String votingServiceUrl
    ) {
        this.properties = properties;
        this.sessionServiceUrl = normalize(sessionServiceUrl);
        this.votingServiceUrl = normalize(votingServiceUrl);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(REQUEST_TIMEOUT)
                .build();
    }

    public CompletableFuture<WarmupResult> warmupAsync() {
        if (!properties.isEnabled()) {
            WarmupResult disabledResult = new WarmupResult(true, Instant.now(), Instant.now(), List.of());
            return CompletableFuture.completedFuture(disabledResult);
        }

        while (true) {
            CompletableFuture<WarmupResult> current = inFlightWarmup.get();
            if (current != null) {
                return current;
            }

            CompletableFuture<WarmupResult> started = CompletableFuture.supplyAsync(this::runWarmup);
            if (inFlightWarmup.compareAndSet(null, started)) {
                started.whenComplete((ignored, throwable) -> inFlightWarmup.compareAndSet(started, null));
                return started;
            }
        }
    }

    private WarmupResult runWarmup() {
        Instant startedAt = Instant.now();
        Instant deadline = startedAt.plus(properties.getTimeout());
        List<ServiceTarget> targets = configuredTargets();

        if (targets.isEmpty()) {
            log.info("Warmup skipped: upstream URLs are not configured");
            return new WarmupResult(true, startedAt, Instant.now(), List.of());
        }

        log.info("Starting upstream warmup for {} service(s)", targets.size());

        List<CompletableFuture<ServiceWarmupStatus>> futures = targets.stream()
                .map(target -> CompletableFuture.supplyAsync(() -> warmTarget(target, deadline)))
                .toList();

        List<ServiceWarmupStatus> statuses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        boolean allReady = statuses.stream().allMatch(ServiceWarmupStatus::ready);
        Instant finishedAt = Instant.now();

        if (allReady) {
            log.info("Upstream warmup completed successfully in {} ms", Duration.between(startedAt, finishedAt).toMillis());
        } else {
            log.warn("Upstream warmup finished with failures in {} ms", Duration.between(startedAt, finishedAt).toMillis());
        }

        return new WarmupResult(allReady, startedAt, finishedAt, statuses);
    }

    private ServiceWarmupStatus warmTarget(ServiceTarget target, Instant deadline) {
        int attempts = 0;
        String lastMessage = "timeout";

        while (Instant.now().isBefore(deadline)) {
            attempts++;

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(target.healthUrl()))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    log.info("Warmup succeeded for {} after {} attempt(s)", target.name(), attempts);
                    return new ServiceWarmupStatus(target.name(), target.baseUrl(), true, attempts, "HTTP " + status);
                }

                lastMessage = "HTTP " + status;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new ServiceWarmupStatus(target.name(), target.baseUrl(), false, attempts, ex.getMessage());
            } catch (IOException ex) {
                lastMessage = ex.getClass().getSimpleName();
            } catch (IllegalArgumentException ex) {
                return new ServiceWarmupStatus(target.name(), target.baseUrl(), false, attempts, "Invalid URL");
            }

            sleep(properties.getRetryDelay());
        }

        log.warn("Warmup failed for {} after {} attempt(s): {}", target.name(), attempts, lastMessage);
        return new ServiceWarmupStatus(target.name(), target.baseUrl(), false, attempts, lastMessage);
    }

    private List<ServiceTarget> configuredTargets() {
        List<ServiceTarget> targets = new ArrayList<>();

        if (!sessionServiceUrl.isBlank()) {
            targets.add(new ServiceTarget("session", sessionServiceUrl));
        }

        if (!votingServiceUrl.isBlank()) {
            targets.add(new ServiceTarget("voting", votingServiceUrl));
        }

        return targets;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static String normalize(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record ServiceTarget(String name, String baseUrl) {
        private String healthUrl() {
            return baseUrl + "/actuator/health";
        }
    }
}
