package com.prosperr.crm.lifecycle.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily jobs (IST). Disabled in tests with crm.jobs.enabled=false. */
@Component
@ConditionalOnProperty(name = "crm.jobs.enabled", havingValue = "true", matchIfMissing = true)
class LifecycleJobs {
    private final LifecycleService lifecycle;
    LifecycleJobs(LifecycleService lifecycle) { this.lifecycle = lifecycle; }

    @Scheduled(cron = "0 30 6 * * *", zone = "Asia/Kolkata")
    void flag() { lifecycle.flagStaleAccounts(); }

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Kolkata")
    void revisits() { lifecycle.openRevisits(); }
}
