package com.prosperr.crm.reporting.web;

import com.prosperr.crm.reporting.application.PublicStatsService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/** Unauthenticated: totals and event kinds only, for the sign-in page. */
@RestController
public class PublicStatsController {
    private final PublicStatsService stats;

    public PublicStatsController(PublicStatsService stats) { this.stats = stats; }

    @GetMapping("/public/stats")
    public ResponseEntity<PublicStatsService.Stats> stats() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(stats.stats());
    }
}
