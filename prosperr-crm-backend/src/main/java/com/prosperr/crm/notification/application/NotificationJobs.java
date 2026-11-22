package com.prosperr.crm.notification.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "crm.jobs.enabled", havingValue = "true", matchIfMissing = true)
class NotificationJobs {
    private final ReminderService reminders;
    NotificationJobs(ReminderService reminders) { this.reminders = reminders; }

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    void digest() { reminders.dailyDigest(); reminders.followUps(); }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Kolkata")
    void tomorrow() { reminders.meetingsTomorrow(); }

    @Scheduled(cron = "0 15 * * * *", zone = "Asia/Kolkata")
    void noOutcome() { reminders.meetingsWithoutOutcome(); }
}
