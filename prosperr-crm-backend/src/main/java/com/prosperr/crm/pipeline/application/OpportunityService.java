package com.prosperr.crm.pipeline.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.pipeline.api.StageChanged;
import com.prosperr.crm.pipeline.domain.StagePolicy;
import com.prosperr.crm.pipeline.domain.StagnationPolicy;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.jdbc.DbSession;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Opportunity stage, temperature, MRR, Won/Lost (PRD M6-4..M6-11). */
@Service
public class OpportunityService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final ConfigApi config;
    private final AuditApi audit;
    private final DbSession session;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final StagePolicy stages = new StagePolicy();
    private final StagnationPolicy stagnation = new StagnationPolicy();

    public OpportunityService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, AccountGuard guard, ConfigApi config,
                              AuditApi audit, DbSession session, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.guard = guard; this.config = config; this.audit = audit;
        this.session = session; this.events = events; this.clock = clock;
    }

    public record Opp(UUID id, UUID accountId, LifecycleStage stage, Instant stageEnteredAt, Temperature temperature,
                      BigDecimal expectedMrr, Integer licenseCount, UUID stagnationReasonId, String stagnationNote,
                      String nextStep, LocalDate nextStepDue, LocalDate expectedCloseDate, Instant closedAt,
                      UUID lostReasonId, String outcomeNote, Instant implementationStartedAt, int version) {}

    Opp load(UUID id) {
        return jdbc.query("""
                SELECT id, account_id, stage, stage_entered_at, temperature, expected_mrr, license_count, stagnation_reason_id,
                       stagnation_note, next_step, next_step_due, expected_close_date, closed_at, lost_reason_id, outcome_note,
                       implementation_started_at, version FROM crm.opportunity WHERE id = :id""", Map.of("id", id),
                (rs, i) -> new Opp(Db.uuid(rs, "id"), Db.uuid(rs, "account_id"), LifecycleStage.valueOf(rs.getString("stage")),
                        Db.instant(rs, "stage_entered_at"), Db.enumOf(Temperature.class, rs.getString("temperature")),
                        rs.getBigDecimal("expected_mrr"), Db.integer(rs, "license_count"), Db.uuid(rs, "stagnation_reason_id"),
                        rs.getString("stagnation_note"), rs.getString("next_step"), Db.date(rs, "next_step_due"),
                        Db.date(rs, "expected_close_date"), Db.instant(rs, "closed_at"), Db.uuid(rs, "lost_reason_id"),
                        rs.getString("outcome_note"), Db.instant(rs, "implementation_started_at"), rs.getInt("version")))
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Opportunity", id));
    }

    public Map<String, Object> get(UUID id, Actor actor) {
        Opp o = load(id);
        guard.require(actor, Action.VIEW_PIPELINE, o.accountId());
        AccountView a = accounts.get(o.accountId());
        Integer days = config.stageThreshold(o.stage()).stagnationDays();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("opportunity", o);
        out.put("stagnationReasonRequired", stagnation.reasonRequired(a.sizeBucket(), o.stage(), o.stageEnteredAt(), days,
                Instant.now(clock), o.stagnationReasonId() != null));
        out.put("checklist", checklist(id));
        out.put("blockedBy", stages.blockedReason(completedMeetings(o.accountId()), exitDone(id, "L1"), exitDone(id, "L2")));
        out.put("temperatureHistory", jdbc.queryForList("""
                SELECT th.from_value AS "from", th.to_value AS "to", th.changed_at AS "changedAt", u.full_name AS "changedBy"
                  FROM crm.temperature_history th JOIN crm.app_user u ON u.id = th.changed_by
                 WHERE th.opportunity_id = :o ORDER BY th.changed_at""", Map.of("o", id)));
        return out;
    }

    public record OppUpdate(Temperature temperature, BigDecimal expectedMrr, Integer licenseCount, UUID stagnationReasonId,
                            String stagnationNote, String nextStep, LocalDate nextStepDue, LocalDate expectedCloseDate,
                            Integer version) {}

    @Transactional
    public Opp update(UUID id, OppUpdate u, Actor actor) {
        Opp o = load(id);
        guard.require(actor, Action.UPDATE_OPPORTUNITY, o.accountId());
        if (u.expectedMrr() != null && u.expectedMrr().signum() < 0) throw new DomainException("INVALID_MRR", "Expected MRR can't be negative");
        if (u.licenseCount() != null && u.licenseCount() < 0) throw new DomainException("INVALID_LICENSES", "Licence count can't be negative");
        int v = u.version() == null ? o.version() : u.version();
        int n = jdbc.update("""
                UPDATE crm.opportunity SET temperature = coalesce(:t, temperature), expected_mrr = coalesce(:mrr, expected_mrr),
                  license_count = coalesce(:lic, license_count), stagnation_reason_id = coalesce(:sr, stagnation_reason_id),
                  stagnation_note = coalesce(:sn, stagnation_note), next_step = coalesce(:ns, next_step),
                  next_step_due = coalesce(:nd, next_step_due), expected_close_date = coalesce(:cd, expected_close_date),
                  version = version + 1
                WHERE id = :id AND version = :v""",
                new MapSqlParameterSource().addValue("t", Db.name(u.temperature())).addValue("mrr", u.expectedMrr())
                        .addValue("lic", u.licenseCount()).addValue("sr", u.stagnationReasonId()).addValue("sn", u.stagnationNote())
                        .addValue("ns", u.nextStep()).addValue("nd", u.nextStepDue()).addValue("cd", u.expectedCloseDate())
                        .addValue("id", id).addValue("v", v));
        if (n == 0) throw new ObjectOptimisticLockingFailureException("opportunity", id);
        if (u.temperature() != null && u.temperature() != o.temperature()) {
            jdbc.update("""
                    INSERT INTO crm.temperature_history (opportunity_id, from_value, to_value, changed_by)
                    VALUES (:o, :f, :t, :by)""",
                    new MapSqlParameterSource().addValue("o", id).addValue("f", Db.name(o.temperature()))
                            .addValue("t", u.temperature().name()).addValue("by", actor.userId()));
            audit.change("opportunity", id, "temperature", o.temperature(), u.temperature(), actor.userId());
        }
        if (u.nextStep() != null || u.nextStepDue() != null) {
            accounts.setNextStep(o.accountId(), u.nextStep() != null ? u.nextStep() : o.nextStep(),
                    u.nextStepDue() != null ? u.nextStepDue() : o.nextStepDue());
        }
        return load(id);
    }

    public record StageMove(LifecycleStage target, UUID lostReasonId, String note) {}

    /** Negotiation, Won, Lost, Implementation and Multithreading are set by the AE (M6-4). */
    @Transactional
    public Opp moveStage(UUID id, StageMove m, Actor actor) {
        Opp o = load(id);
        guard.require(actor, Action.UPDATE_OPPORTUNITY, o.accountId());
        stages.checkManualMove(o.stage(), m.target());
        if (m.target() == LifecycleStage.LOST) {
            if (m.lostReasonId() == null) throw new DomainException("LOST_REASON_REQUIRED", "A lost reason is required");
            if (config.picklistItem(m.lostReasonId()).map(p -> p.requiresRemark()).orElse(false) && (m.note() == null || m.note().isBlank())) {
                throw new DomainException("REMARK_REQUIRED", "This reason needs a remark");
            }
        }
        jdbc.update("""
                UPDATE crm.opportunity SET lost_reason_id = coalesce(:lr, lost_reason_id), outcome_note = coalesce(:note, outcome_note),
                  closed_at = CASE WHEN :t IN ('WON','LOST') THEN now() ELSE closed_at END,
                  implementation_started_at = CASE WHEN :t = 'IMPLEMENTATION' THEN now() ELSE implementation_started_at END
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("lr", m.lostReasonId()).addValue("note", m.note())
                        .addValue("t", m.target().name()).addValue("id", id));
        writeStage(o, m.target(), actor.userId());
        return load(id);
    }

    /** Re-derive the stage from completed meetings and logged exit steps (M6-4, M6-10, M6-11). */
    @Transactional
    public void reevaluate(UUID opportunityId, UUID actor) {
        Opp o = load(opportunityId);
        LifecycleStage target = stages.derive(o.stage(), completedMeetings(o.accountId()), exitDone(opportunityId, "L1"),
                exitDone(opportunityId, "L2"));
        if (target == o.stage()) return;
        // step through each stage so a combined L1+L2 meeting is counted once at each stage (0 days between)
        for (LifecycleStage s : List.of(LifecycleStage.L1, LifecycleStage.L2, LifecycleStage.L3)) {
            Opp cur = load(opportunityId);
            if (s.rank() > cur.stage().rank() && s.rank() <= target.rank()) writeStage(cur, s, actor);
        }
    }

    void writeStage(Opp o, LifecycleStage target, UUID actor) {
        if (o.stage() == target) return;
        session.actAs(actor);
        jdbc.update("UPDATE crm.opportunity SET stage = :s, version = version + 1 WHERE id = :id",
                Map.of("s", target.name(), "id", o.id()));
        audit.change("opportunity", o.id(), "stage", o.stage(), target, actor);
        events.publishEvent(new StageChanged(o.accountId(), o.id(), o.stage(), target, actor));
    }

    List<StagePolicy.CompletedMeeting> completedMeetings(UUID accountId) {
        return jdbc.query("""
                SELECT level, also_completes_l2 FROM crm.meeting WHERE account_id = :a AND status = 'COMPLETED'""",
                Map.of("a", accountId), (rs, i) -> new StagePolicy.CompletedMeeting(MeetingLevel.valueOf(rs.getString(1)), rs.getBoolean(2)));
    }

    boolean exitDone(UUID opportunityId, String stage) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM crm.stage_checklist_item i
                  JOIN crm.opportunity_checklist c ON c.checklist_item_id = i.id AND c.opportunity_id = :o AND c.is_done
                 WHERE i.stage = :s AND i.is_exit_step AND i.is_active""", Map.of("o", opportunityId, "s", stage), Integer.class);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ checklist (M6-9, M6-10)

    public List<Map<String, Object>> checklist(UUID opportunityId) {
        return jdbc.queryForList("""
                SELECT i.id, i.stage, i.code, i.label, i.is_exit_step AS "exitStep", i.requires_attachment AS "requiresAttachment",
                       coalesce(c.is_done, false) AS "done", c.done_at AS "doneAt", u.full_name AS "doneBy",
                       c.attachment_id AS "attachmentId", c.note
                  FROM crm.stage_checklist_item i
                  LEFT JOIN crm.opportunity_checklist c ON c.checklist_item_id = i.id AND c.opportunity_id = :o
                  LEFT JOIN crm.app_user u ON u.id = c.done_by
                 WHERE i.is_active ORDER BY i.stage, i.sort_order""", Map.of("o", opportunityId));
    }

    public record Tick(boolean done, String note, UUID attachmentId) {}

    @Transactional
    public List<Map<String, Object>> tick(UUID opportunityId, UUID itemId, Tick t, Actor actor) {
        Opp o = load(opportunityId);
        guard.require(actor, Action.UPDATE_MEETINGS, o.accountId());
        ConfigApi.ChecklistItem item = config.checklistItems().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Checklist item", itemId));
        if (t.done() && item.requiresAttachment() && t.attachmentId() == null) {
            throw new DomainException("ATTACHMENT_REQUIRED", "'" + item.label() + "' needs the file or link attached");
        }
        jdbc.update("""
                INSERT INTO crm.opportunity_checklist (opportunity_id, checklist_item_id, is_done, done_at, done_by, attachment_id, note)
                VALUES (:o, :i, :d, CASE WHEN :d THEN now() END, CASE WHEN :d THEN CAST(:by AS uuid) END, :att, :note)
                ON CONFLICT (opportunity_id, checklist_item_id) DO UPDATE SET is_done = EXCLUDED.is_done,
                  done_at = EXCLUDED.done_at, done_by = EXCLUDED.done_by, attachment_id = EXCLUDED.attachment_id,
                  note = EXCLUDED.note""",
                new MapSqlParameterSource().addValue("o", opportunityId).addValue("i", itemId).addValue("d", t.done())
                        .addValue("by", actor.userId()).addValue("att", t.attachmentId()).addValue("note", t.note()));
        if (item.exitStep()) reevaluate(opportunityId, actor.userId());
        return checklist(opportunityId);
    }
}
