package com.prosperr.crm.lifecycle.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.lifecycle.api.LifecycleEvents;
import com.prosperr.crm.lifecycle.domain.InactivityPolicy;
import com.prosperr.crm.pipeline.api.PipelineApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** PRD M8: "to be moved" queue, Inactive with reason, revisit dates, revival queue, reactivation. */
@Service
public class LifecycleService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final PipelineApi pipeline;
    private final TaskApi tasks;
    private final ConfigApi config;
    private final UserDirectory users;
    private final AuditApi audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final InactivityPolicy policy = new InactivityPolicy();

    public LifecycleService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, AccountGuard guard, PipelineApi pipeline,
                            TaskApi tasks, ConfigApi config, UserDirectory users, AuditApi audit,
                            ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.guard = guard; this.pipeline = pipeline; this.tasks = tasks;
        this.config = config; this.users = users; this.audit = audit; this.events = events; this.clock = clock;
    }

    LocalDate today() { return LocalDate.now(clock.withZone(Ist.ZONE)); }

    /**
     * M8-1: flag active accounts with no completed meeting or activity for N days (per-stage threshold).
     * Runs daily; returns the number of accounts flagged.
     */
    @Transactional
    public int flagStaleAccounts() {
        Instant now = Instant.now(clock);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.ae_owner_id
                  FROM crm.account a JOIN crm.stage_threshold t ON t.stage = a.lifecycle_stage
                 WHERE a.account_status = 'ACTIVE' AND a.review_flagged_at IS NULL
                   AND a.lifecycle_stage NOT IN ('WON','LOST','IMPLEMENTATION')
                   AND greatest(a.created_at, coalesce(a.last_activity_at, a.created_at),
                                coalesce((SELECT max(m.scheduled_start) FROM crm.meeting m
                                           WHERE m.account_id = a.id AND m.status = 'COMPLETED'), a.created_at))
                       <= CAST(:now AS timestamptz) - make_interval(days => t.inactivity_flag_days)""",
                new MapSqlParameterSource().addValue("now", Db.ts(now)));
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            accounts.flagForReview(id, now);
            events.publishEvent(new LifecycleEvents.AccountFlaggedForReview(id, (UUID) r.get("ae_owner_id")));
        }
        return rows.size();
    }

    /** M8-1 queue. AEs see their own; managers all or by AE. */
    public List<Map<String, Object>> toBeMoved(UUID aeId) {
        return jdbc.queryForList("""
                SELECT a.id, a.name, a.lifecycle_stage AS "stage", a.size_bucket_code AS "sizeBucket",
                       a.last_activity_at AS "lastActivityAt", a.review_flagged_at AS "flaggedAt", u.full_name AS "aeOwner"
                  FROM crm.account a LEFT JOIN crm.app_user u ON u.id = a.ae_owner_id
                 WHERE a.review_flagged_at IS NOT NULL AND a.account_status = 'ACTIVE'
                   AND (CAST(:ae AS uuid) IS NULL OR a.ae_owner_id = :ae)
                 ORDER BY a.review_flagged_at""", new MapSqlParameterSource().addValue("ae", aeId));
    }

    public record MoveInactive(UUID reasonId, String remark, LocalDate revisitOn) {}

    /** M8-2 / M8-3 / M8-5. Returns "INACTIVE" or "PENDING_APPROVAL". */
    @Transactional
    public String moveToInactive(UUID accountId, MoveInactive m, Actor actor) {
        guard.require(actor, Action.MOVE_INACTIVE, accountId);
        AccountView a = accounts.get(accountId);
        if (a.status() == AccountStatus.INACTIVE) throw new DomainException("ALREADY_INACTIVE", "Account is already inactive");
        ConfigApi.PicklistItem reason = m.reasonId() == null ? null : config.picklistItem(m.reasonId())
                .filter(p -> p.key().equals("INACTIVE_REASON")).orElse(null);
        policy.checkMoveToInactive(reason != null, reason != null && reason.requiresRevisitDate(),
                reason != null && reason.requiresRemark(), m.remark(), m.revisitOn(), today());

        if (config.flag("require_inactive_approval", false) && !actor.isLeadership()) {
            UUID manager = users.get(actor.userId()).managerId();
            tasks.create(new TaskApi.NewTask(accountId, null, null, null, TaskType.REVIEW_INACTIVE,
                    "Approve move to Inactive: " + a.name() + " (" + reason.label() + ") — " + m.remark(),
                    Instant.now(clock), manager != null ? manager : actor.userId(), TaskSource.SYSTEM, null, actor.userId()));
            events.publishEvent(new LifecycleEvents.InactiveApprovalRequested(accountId, actor.userId(), manager));
            return "PENDING_APPROVAL";
        }
        accounts.markInactive(accountId, m.reasonId(), m.remark(), reason.requiresRevisitDate() ? m.revisitOn() : m.revisitOn(),
                Instant.now(clock));
        statusHistory(accountId, AccountStatus.ACTIVE, AccountStatus.INACTIVE, a.stage(), m.reasonId(), m.remark(), m.revisitOn(),
                null, actor.isLeadership() ? actor.userId() : null, actor.userId());
        audit.record("account", accountId, AuditApi.Action.STATUS, "account_status", "ACTIVE", "INACTIVE", actor.userId());
        events.publishEvent(new LifecycleEvents.AccountMovedInactive(accountId, a.aeOwnerId(), reason.label(), actor.userId()));
        return "INACTIVE";
    }

    public record KeepActive(String nextStep, LocalDate nextStepDue) {}

    /** M8-2: keep active needs a next step and date; clears the flag and creates a follow-up task. */
    @Transactional
    public void keepActive(UUID accountId, KeepActive k, Actor actor) {
        guard.require(actor, Action.MOVE_INACTIVE, accountId);
        policy.checkKeepActive(k.nextStep(), k.nextStepDue(), today());
        AccountView a = accounts.get(accountId);
        accounts.setNextStep(accountId, k.nextStep(), k.nextStepDue());
        jdbc.update("UPDATE crm.account SET review_flagged_at = NULL WHERE id = :a", Map.of("a", accountId));
        tasks.create(new TaskApi.NewTask(accountId, null, null, null, TaskType.FOLLOW_UP, k.nextStep(),
                k.nextStepDue().atTime(10, 0).atZone(Ist.ZONE).toInstant(),
                a.aeOwnerId() != null ? a.aeOwnerId() : actor.userId(), TaskSource.MANUAL, null, actor.userId()));
    }

    public record Reactivate(UUID reasonId, String note, UUID newStakeholderId) {}

    /** M8-7: back to the last stage, or to L1 when a new stakeholder is engaged; all history stays. */
    @Transactional
    public LifecycleStage reactivate(UUID accountId, Reactivate r, Actor actor) {
        guard.require(actor, Action.REACTIVATE, accountId);
        AccountView a = accounts.get(accountId);
        if (a.status() != AccountStatus.INACTIVE) throw new DomainException("NOT_INACTIVE", "Account is not inactive");
        LifecycleStage target = policy.reactivationStage(a.stage(), r.newStakeholderId() != null);
        accounts.markActive(accountId);
        if (target != a.stage()) pipeline.setStage(accountId, target, actor.userId());
        statusHistory(accountId, AccountStatus.INACTIVE, AccountStatus.ACTIVE, a.stage(), r.reasonId(), r.note(), null,
                target.name(), null, actor.userId());
        jdbc.update("UPDATE crm.task SET status = 'DONE', completed_at = now(), completed_by = :by WHERE account_id = :a AND type = 'REVIVAL' AND status = 'OPEN'",
                Map.of("a", accountId, "by", actor.userId()));
        audit.record("account", accountId, AuditApi.Action.STATUS, "account_status", "INACTIVE", "ACTIVE", actor.userId());
        events.publishEvent(new LifecycleEvents.AccountReactivated(accountId, a.aeOwnerId(), target, actor.userId()));
        return target;
    }

    /** M8-5: on the revisit date the account enters the BDR revival queue; BDRs on the account get a task. */
    @Transactional
    public int openRevisits() {
        LocalDate today = today();
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT a.id, a.name FROM crm.account a
                 WHERE a.account_status = 'INACTIVE' AND a.revisit_on <= :d
                   AND NOT EXISTS (SELECT 1 FROM crm.task t WHERE t.account_id = a.id AND t.type = 'REVIVAL' AND t.status = 'OPEN')""",
                Map.of("d", today));
        for (Map<String, Object> row : due) {
            UUID id = (UUID) row.get("id");
            List<UUID> bdrs = jdbc.queryForList("SELECT bdr_id FROM crm.account_bdr WHERE account_id = :a", Map.of("a", id), UUID.class);
            for (UUID b : bdrs) {
                tasks.create(new TaskApi.NewTask(id, null, null, null, TaskType.REVIVAL,
                        "Revisit " + row.get("name") + ": find a new stakeholder", Instant.now(clock), b,
                        TaskSource.SYSTEM, null, null));
            }
            events.publishEvent(new LifecycleEvents.RevisitDue(id, bdrs));
        }
        return due.size();
    }

    /** M8-6: revival queue sorted by size bucket (largest first) and reason. */
    public List<Map<String, Object>> revivalQueue(UUID bdrId) {
        return jdbc.queryForList("""
                SELECT v.account_id AS "accountId", v.name, v.size_bucket_code AS "sizeBucket", v.lifecycle_stage AS "stage",
                       pv.label AS "inactiveReason", v.revisit_on AS "revisitOn", u.full_name AS "aeOwner"
                  FROM crm.v_revival_queue v LEFT JOIN crm.picklist_value pv ON pv.id = v.inactive_reason_id
                  LEFT JOIN crm.app_user u ON u.id = v.ae_owner_id
                 WHERE CAST(:b AS uuid) IS NULL OR EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = v.account_id AND ab.bdr_id = :b)
                 ORDER BY v.size_rank DESC NULLS LAST, pv.sort_order, v.revisit_on""",
                new MapSqlParameterSource().addValue("b", bdrId));
    }

    public List<Map<String, Object>> statusHistoryFor(UUID accountId) {
        return jdbc.queryForList("""
                SELECT h.from_status AS "from", h.to_status AS "to", h.stage_at_change AS "stage", pv.label AS "reason",
                       h.remark, h.revisit_on AS "revisitOn", h.returned_to_stage AS "returnedTo", u.full_name AS "changedBy",
                       h.changed_at AS "changedAt"
                  FROM crm.account_status_history h JOIN crm.app_user u ON u.id = h.changed_by
                  LEFT JOIN crm.picklist_value pv ON pv.id = h.reason_id
                 WHERE h.account_id = :a ORDER BY h.changed_at""", Map.of("a", accountId));
    }

    void statusHistory(UUID accountId, AccountStatus from, AccountStatus to, LifecycleStage stage, UUID reasonId, String remark,
                       LocalDate revisitOn, String returnedTo, UUID approvedBy, UUID by) {
        jdbc.update("""
                INSERT INTO crm.account_status_history (account_id, from_status, to_status, stage_at_change, reason_id, remark,
                                                        revisit_on, returned_to_stage, approved_by, changed_by)
                VALUES (:a, :f, :t, :s, :r, :rem, :rv, :ret, :ap, :by)""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("f", from.name()).addValue("t", to.name())
                        .addValue("s", stage.name()).addValue("r", reasonId).addValue("rem", remark).addValue("rv", revisitOn)
                        .addValue("ret", returnedTo).addValue("ap", approvedBy).addValue("by", by));
    }
}
