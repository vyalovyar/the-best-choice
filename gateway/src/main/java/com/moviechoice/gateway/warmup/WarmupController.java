package com.moviechoice.gateway.warmup;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WarmupController {

    private final UpstreamWarmupService upstreamWarmupService;

    public WarmupController(UpstreamWarmupService upstreamWarmupService) {
        this.upstreamWarmupService = upstreamWarmupService;
    }

    @GetMapping("/internal/warmup")
    public ResponseEntity<WarmupResult> warmup() {
        WarmupResult result = upstreamWarmupService.warmupAsync().join();
        HttpStatus status = result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(result);
    }
}
