package com.prosperr.crm.prospecting.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.account.api.StakeholderAdded;
import com.prosperr.crm.account.api.StakeholderEngagementChanged;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.shared.domain.ActivityType;
import com.prosperr.crm.shared.domain.Zone;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivityService implements ActivityApi {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final Clock clock;

    public ActivityService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, AccountGuard guard, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.guard = guard; this.clock = clock;
    }

    /** zone_at_time records whether the touch happened before or after L1, for timeline privacy (M7-7). */
    @Override
    @Transactional
    public UUID log(NewActivity a) {
        Zone zone = accounts.get(a.accountId()).zone();
        return jdbc.queryForObject("""
                INSERT INTO crm.activity (account_id, stakeholder_id, opportunity_id, meeting_id, email_message_id, phone_id,
                                          type, call_outcome_id, remarks, occurred_at, logged_by, zone_at_time)
                VALUES (:a, :s, :o, :m, :e, :p, :type, :outcome, :remarks, :at, :by, :zone) RETURNING id""",
                new MapSqlParameterSource().addValue("a", a.accountId()).addValue("s", a.stakeholderId())
                        .addValue("o", a.opportunityId()).addValue("m", a.meetingId()).addValue("e", a.emailMessageId())
                        .addValue("p", a.phoneId()).addValue("type", a.type().name()).addValue("outcome", a.callOutcomeId())
                        .addValue("remarks", a.remarks())
                        .addValue("at", Db.ts(a.occurredAt() == null ? Instant.now(clock) : a.occurredAt()))
                        .addValue("by", a.loggedBy()).addValue("zone", zone.name()), UUID.class);
    }

    public record ManualActivity(UUID accountId, UUID stakeholderId, ActivityType type, String remarks, Instant occurredAt) {}

    /** WhatsApp, LinkedIn, office visit, note or a manually logged email (M2-4: unlimited activities). */
    @Transactional
    public UUID logManual(ManualActivity m, Actor actor) {
        Zone zone = accounts.get(m.accountId()).zone();
        guard.require(actor, zone == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.LOG_ACTIVITY, m.accountId());
        if (m.type() == ActivityType.CALL) throw new IllegalArgumentException("Log calls through the call outcome endpoint");
        return log(new NewActivity(m.accountId(), m.stakeholderId(), null, null, null, null, m.type(), null,
                m.remarks(), m.occurredAt(), actor.userId()));
    }

    /** Engagement changes appear on the multithreading timeline (M7-6). */
    @EventListener
    public void onEngagement(StakeholderEngagementChanged e) {
        if (e.changedBy() == null) return;
        log(new NewActivity(e.accountId(), e.stakeholderId(), null, null, null, null, ActivityType.STATUS_CHANGE, null,
                (e.from() == null ? "" : e.from() + " → ") + e.to(), null, e.changedBy()));
    }

    /** Referral links show who introduced whom (M7-6). */
    @EventListener
    public void onStakeholderAdded(StakeholderAdded e) {
        if (e.referredById() == null) return;
        String by = jdbc.queryForObject("SELECT first_name || coalesce(' ' || last_name, '') FROM crm.stakeholder WHERE id = :id",
                Map.of("id", e.referredById()), String.class);
        log(new NewActivity(e.accountId(), e.stakeholderId(), null, null, null, null, ActivityType.REFERRAL, null,
                "Referred by " + by, null, e.addedBy()));
    }
}
