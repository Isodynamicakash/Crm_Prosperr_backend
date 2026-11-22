package com.prosperr.crm.assignment.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.assignment.api.AccountAssigned;
import com.prosperr.crm.assignment.domain.*;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.pipeline.api.PipelineApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.domain.AssignmentMethod;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** PRD M5 / BRD 7: manager-controlled AE assignment with a capacity recommendation; never automatic (M5-3). */
@Service
public class AssignmentService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final StakeholderApi stakeholders;
    private final PipelineApi pipeline;
    private final TaskApi tasks;
    private final UserDirectory users;
    private final AuditApi audit;
    private final ApplicationEventPublisher events;
    private final Map<String, AllocationStrategy> strategies = Map.of(
            "CAPACITY", new CapacityAllocation(), "ROUND_ROBIN", new RoundRobinAllocation());

    public AssignmentService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, StakeholderApi stakeholders,
                             PipelineApi pipeline, TaskApi tasks, UserDirectory users, AuditApi audit,
                             ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.accounts = accounts; this.stakeholders = stakeholders; this.pipeline = pipeline;
        this.tasks = tasks; this.users = users; this.audit = audit; this.events = events;
    }

    /** BRD 7.1 AE capacity: active, L1 pending, L2, L3, negotiation, inactive, total, weighted load, last assigned. */
    public List<AeLoad> loads() {
        return jdbc.query("""
                SELECT c.ae_id, c.full_name, c.active_accounts, c.l1_pending, c.l2_pipeline, c.l3_pipeline, c.negotiation,
                       c.inactive_accounts, c.total_assigned, c.weighted_load,
                       (SELECT max(h.assigned_at) FROM crm.assignment_history h WHERE h.new_owner_id = c.ae_id AND h.owner_type = 'AE') AS last_assigned
                  FROM crm.v_ae_capacity c ORDER BY c.full_name""", Map.of(),
                (rs, i) -> new AeLoad(Db.uuid(rs, "ae_id"), rs.getString("full_name"), rs.getInt("active_accounts"),
                        rs.getInt("l1_pending"), rs.getInt("l2_pipeline"), rs.getInt("l3_pipeline"), rs.getInt("negotiation"),
                        rs.getInt("inactive_accounts"), rs.getInt("total_assigned"), rs.getDouble("weighted_load"),
                        Db.instant(rs, "last_assigned")));
    }

    public record CapacityRow(AeLoad load, Workload.Level workload, int rank) {}

    /** AE allocation dashboard: per-AE workload, indicator and the recommended next AE (BRD 10.4). */
    public Map<String, Object> capacity(String strategy) {
        List<AeLoad> all = loads();
        List<AeLoad> ranked = strategies.getOrDefault(strategy == null ? "CAPACITY" : strategy, new CapacityAllocation()).rank(all);
        List<CapacityRow> rows = new ArrayList<>();
        for (AeLoad l : all) rows.add(new CapacityRow(l, Workload.indicator(l, all), ranked.indexOf(l) + 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("aes", rows);
        out.put("recommended", ranked.isEmpty() ? null : ranked.get(0));
        out.put("strategy", strategy == null ? "CAPACITY" : strategy);
        return out;
    }

    /** M5-1: SQLs waiting for validation or for an AE. */
    public List<Map<String, Object>> queue() {
        return jdbc.queryForList("""
                SELECT s.id AS "submissionId", s.status, a.id AS "accountId", a.name AS "accountName",
                       a.size_bucket_code AS "sizeBucket", s.location_priority AS "locationPriority",
                       s.is_small_exception AS "smallException", u.full_name AS "submittedBy", s.submitted_at AS "submittedAt",
                       (SELECT min(m.scheduled_start) FROM crm.meeting m WHERE m.account_id = a.id AND m.level = 'L1'
                          AND m.status IN ('SCHEDULED','RESCHEDULED')) AS "l1At",
                       ae.full_name AS "aeOwner"
                  FROM crm.sql_submission s JOIN crm.account a ON a.id = s.account_id
                  JOIN crm.app_user u ON u.id = s.submitted_by LEFT JOIN crm.app_user ae ON ae.id = a.ae_owner_id
                 WHERE s.status IN ('MEETING_BOOKED','PENDING_QUALIFICATION','PENDING_SENIOR_CONNECTION','NO_SHOW')
                    OR (s.status = 'QUALIFIED_SQL' AND a.ae_owner_id IS NULL)
                 ORDER BY s.submitted_at""", Map.of());
    }

    public record Assign(UUID aeId, AssignmentMethod method, UUID reasonId, String note) {}

    /** M5-2..M5-4: manual assignment with the recommendation recorded; reassignment needs a reason. */
    @Transactional
    public AccountView assign(UUID accountId, Assign a, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only the sales manager assigns AEs");
        var ae = users.get(a.aeId());
        if (ae.role() != Role.AE || !ae.active()) throw new DomainException("NOT_AN_ACTIVE_AE", ae.fullName() + " is not an active AE");
        AccountView before = accounts.get(accountId);
        UUID previous = before.aeOwnerId();
        if (a.aeId().equals(previous)) return before;
        if (previous != null && a.reasonId() == null && (a.note() == null || a.note().isBlank())) {
            throw new DomainException("REASSIGN_REASON_REQUIRED", "Reassignment needs a reason");
        }
        // lock the AE rows so two managers cannot both push the same AE over capacity at once
        jdbc.queryForList("SELECT id FROM crm.app_user WHERE role = 'AE' AND is_active ORDER BY id FOR UPDATE", Map.of(), UUID.class);
        List<AeLoad> ranked = new CapacityAllocation().rank(loads());
        UUID recommended = ranked.isEmpty() ? null : ranked.get(0).aeId();

        accounts.setAeOwner(accountId, a.aeId());
        pipeline.setMeetingAe(accountId, a.aeId());
        if (previous != null) tasks.moveOpen(accountId, previous, a.aeId());
        history(accountId, "AE", null, previous, a.aeId(), a.method() == null ? AssignmentMethod.MANUAL : a.method(),
                recommended, a.reasonId(), a.note(), actor.userId());
        audit.record("account", accountId, previous == null ? com.prosperr.crm.audit.api.AuditApi.Action.UPDATE
                : com.prosperr.crm.audit.api.AuditApi.Action.REASSIGN, "ae_owner_id", previous, a.aeId(), actor.userId());
        events.publishEvent(new AccountAssigned(accountId, a.aeId(), previous, "AE", actor.userId()));
        return accounts.get(accountId);
    }

    public record ReassignBdr(UUID bdrId, UUID reasonId, String note) {}

    /** Manager moves a prospect stakeholder to another BDR (permission table: reassign a prospect). */
    @Transactional
    public void reassignBdr(UUID stakeholderId, ReassignBdr r, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only the sales manager reassigns prospects");
        var s = stakeholders.get(stakeholderId);
        var bdr = users.get(r.bdrId());
        if (bdr.role() != Role.BDR || !bdr.active()) throw new DomainException("NOT_AN_ACTIVE_BDR", bdr.fullName() + " is not an active BDR");
        stakeholders.setBdrOwner(stakeholderId, r.bdrId());
        jdbc.update("UPDATE crm.task SET assignee_id = :to WHERE stakeholder_id = :s AND status = 'OPEN' AND assignee_id = :from",
                new MapSqlParameterSource().addValue("to", r.bdrId()).addValue("s", stakeholderId).addValue("from", s.bdrOwnerId()));
        history(s.accountId(), "BDR", stakeholderId, s.bdrOwnerId(), r.bdrId(), AssignmentMethod.MANUAL, null, r.reasonId(),
                r.note(), actor.userId());
        events.publishEvent(new AccountAssigned(s.accountId(), r.bdrId(), s.bdrOwnerId(), "BDR", actor.userId()));
    }

    public record BulkMove(UUID accountId, UUID toUserId) {}

    /** M5-5: reassign every account of a deactivated user. AE accounts move to an AE; BDR stakeholders to a BDR. */
    @Transactional
    public int bulkReassign(UUID fromUserId, List<BulkMove> moves, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only the sales manager reassigns accounts");
        UUID reason = jdbc.queryForList("SELECT id FROM crm.picklist_value WHERE picklist_key = 'REASSIGNMENT_REASON' AND code = 'USER_LEFT'",
                Map.of(), UUID.class).stream().findFirst().orElse(null);
        int n = 0;
        for (BulkMove m : moves) {
            var to = users.get(m.toUserId());
            AccountView acc = accounts.get(m.accountId());
            if (to.role() == Role.AE && fromUserId.equals(acc.aeOwnerId())) {
                assign(m.accountId(), new Assign(m.toUserId(), AssignmentMethod.USER_DEACTIVATED, reason, "User deactivated"), actor);
                n++;
            } else if (to.role() == Role.BDR) {
                List<UUID> owned = jdbc.queryForList("SELECT id FROM crm.stakeholder WHERE account_id = :a AND bdr_owner_id = :u",
                        Map.of("a", m.accountId(), "u", fromUserId), UUID.class);
                for (UUID s : owned) reassignBdr(s, new ReassignBdr(m.toUserId(), reason, "User deactivated"), actor);
                tasks.moveOpen(m.accountId(), fromUserId, m.toUserId());
                if (fromUserId.equals(acc.sqlCreditBdrId())) accounts.setSqlCredit(m.accountId(), m.toUserId());
                n++;
            }
        }
        return n;
    }

    void history(UUID accountId, String type, UUID stakeholderId, UUID previous, UUID next, AssignmentMethod method,
                 UUID recommended, UUID reasonId, String note, UUID by) {
        jdbc.update("""
                INSERT INTO crm.assignment_history (account_id, owner_type, stakeholder_id, previous_owner_id, new_owner_id, method,
                                                    recommended_owner_id, reason_id, reason_note, assigned_by)
                VALUES (:a, :t, :s, :p, :n, :m, :rec, :r, :note, :by)""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("t", type).addValue("s", stakeholderId)
                        .addValue("p", previous).addValue("n", next).addValue("m", method.name()).addValue("rec", recommended)
                        .addValue("r", reasonId).addValue("note", note).addValue("by", by));
    }

    public List<Map<String, Object>> historyFor(UUID accountId) {
        return jdbc.queryForList("""
                SELECT h.owner_type AS "ownerType", p.full_name AS "previous", n.full_name AS "new", h.method,
                       rec.full_name AS "recommended", r.label AS "reason", h.reason_note AS "note", b.full_name AS "assignedBy",
                       h.assigned_at AS "assignedAt"
                  FROM crm.assignment_history h LEFT JOIN crm.app_user p ON p.id = h.previous_owner_id
                  JOIN crm.app_user n ON n.id = h.new_owner_id LEFT JOIN crm.app_user rec ON rec.id = h.recommended_owner_id
                  LEFT JOIN crm.picklist_value r ON r.id = h.reason_id JOIN crm.app_user b ON b.id = h.assigned_by
                 WHERE h.account_id = :a ORDER BY h.assigned_at""", Map.of("a", accountId));
    }
}
