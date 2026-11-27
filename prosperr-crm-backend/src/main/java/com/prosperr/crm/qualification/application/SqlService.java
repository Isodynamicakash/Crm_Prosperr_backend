package com.prosperr.crm.qualification.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.account.api.*;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.pipeline.api.MeetingBooked;
import com.prosperr.crm.pipeline.api.MeetingOutcomeRecorded;
import com.prosperr.crm.pipeline.api.PipelineApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.qualification.api.SqlRejected;
import com.prosperr.crm.qualification.api.SqlSubmitted;
import com.prosperr.crm.qualification.api.SqlValidated;
import com.prosperr.crm.qualification.domain.*;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

/** SQL qualification (PRD M4, BRD 4): checklist, submit with L1 booking, validate / reject, AE corrections. */
@Service
public class SqlService {
    private static final Set<EngagementStatus> ENGAGED = EnumSet.of(EngagementStatus.CONNECTED,
            EngagementStatus.MEETING_SCHEDULED, EngagementStatus.MEETING_COMPLETED, EngagementStatus.FOLLOW_UP_PENDING,
            EngagementStatus.REFERRED);
    private static final Set<String> OPEN = Set.of("PENDING_QUALIFICATION", "PENDING_SENIOR_CONNECTION", "MEETING_BOOKED", "NO_SHOW");

    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final StakeholderApi stakeholders;
    private final AccountGuard guard;
    private final ConfigApi config;
    private final PipelineApi pipeline;
    private final TaskApi tasks;
    private final AuditApi audit;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;
    private final QualificationEngine engine = QualificationEngine.standard();

    public SqlService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, StakeholderApi stakeholders, AccountGuard guard,
                      ConfigApi config, PipelineApi pipeline, TaskApi tasks, AuditApi audit,
                      ApplicationEventPublisher events, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.stakeholders = stakeholders; this.guard = guard; this.config = config;
        this.pipeline = pipeline; this.tasks = tasks; this.audit = audit; this.events = events; this.json = json; this.clock = clock;
    }

    public record Bant(UUID budgetId, String budgetNote, UUID authorityId, String authorityNote, UUID needId, String needNote,
                       UUID timelineId, String timelineNote) {
        boolean complete() { return budgetId != null && authorityId != null && needId != null && timelineId != null; }
    }

    public record L1Booking(Instant scheduledStart, Instant scheduledEnd, String meetingLink, List<UUID> attendeeStakeholderIds,
                            boolean alsoCompletesL2) {}

    public record SubmitSql(UUID qualifyingStakeholderId, UUID referringStakeholderId, BusinessRelevance businessRelevance,
                            Bant bant, String smallExceptionReason, IntentRating intentRating, String intentReason,
                            L1Booking l1, Boolean highIntentOverride) {
        /** BDR passes the SQL on high intent, skipping the ICP rules, as long as they say why. */
        boolean override() { return Boolean.TRUE.equals(highIntentOverride); }
        boolean overrideReady() { return override() && intentReason != null && !intentReason.isBlank(); }
    }

    /** With a high-intent override only what the SQL and the L1 booking physically need stays mandatory. */
    private static final Set<String> NEEDED_FOR_OVERRIDE = Set.of("qualifyingStakeholder", "businessRelevance", "l1Booking");

    public record Checklist(List<CriterionResult> criteria, List<String> missingFields, boolean canSubmit,
                            boolean needsApproval, Integer stakeholderLevel, ConnectionType connectionType) {}

    // ------------------------------------------------------------------ rules

    List<QualificationEngine.ActiveRule> activeRules() {
        return jdbc.query("SELECT code, config::text FROM crm.icp_rule WHERE is_active ORDER BY code", Map.of(), (rs, i) -> {
            try {
                Map<String, Object> cfg = json.readValue(rs.getString(2), new TypeReference<Map<String, Object>>() {});
                return new QualificationEngine.ActiveRule(rs.getString(1), cfg);
            } catch (Exception e) {
                return new QualificationEngine.ActiveRule(rs.getString(1), Map.of());
            }
        });
    }

    record Facts(AccountView account, StakeholderView stakeholder, StakeholderView referrer, LocationPriority priority) {}

    Facts facts(UUID accountId, UUID stakeholderId, UUID referrerId) {
        AccountView a = accounts.get(accountId);
        StakeholderView s = stakeholderId == null ? null : stakeholders.get(stakeholderId);
        if (s != null && !s.accountId().equals(accountId)) throw new DomainException("WRONG_ACCOUNT", "Stakeholder is not at this account");
        UUID refId = referrerId != null ? referrerId : (s == null ? null : s.referredById());
        StakeholderView r = refId == null ? null : stakeholders.get(refId);
        LocationPriority p = config.city(a.cityId()).map(ConfigApi.CityView::priority).orElse(null);
        return new Facts(a, s, r, p);
    }

    QualificationInput input(Facts f, boolean smallException, String smallReason, boolean smallApproved,
                             IntentRating intent, String intentReason, boolean intentConfirmed, Bant bant) {
        Bant b = bant == null ? new Bant(null, null, null, null, null, null, null, null) : bant;
        return new QualificationInput(
                f.stakeholder() == null ? null : f.stakeholder().level(),
                f.stakeholder() != null && (ENGAGED.contains(f.stakeholder().engagement())),
                f.referrer() == null ? null : f.referrer().level(),
                f.referrer() != null && f.referrer().accountId().equals(f.account().id()),
                f.account().licensePotential(), f.account().sizeBucket(), smallException, smallReason, smallApproved,
                f.priority(), intent, intentReason, intentConfirmed,
                b.budgetId() != null, b.authorityId() != null, b.needId() != null, b.timelineId() != null);
    }

    /** M4-1: checklist with the ICP criteria checked automatically from account and stakeholder data. */
    @Transactional(readOnly = true)
    public Checklist checklist(UUID accountId, SubmitSql req, Actor actor) {
        guard.require(actor, Action.EDIT_SQL, accountId);
        Facts f = facts(accountId, req.qualifyingStakeholderId(), req.referringStakeholderId());
        IntentRating intent = req.intentRating() != null ? req.intentRating() : f.account().intentRating();
        String intentReason = req.intentReason() != null ? req.intentReason() : f.account().intentReason();
        boolean small = req.smallExceptionReason() != null && !req.smallExceptionReason().isBlank();
        QualificationEngine.Result r = engine.evaluate(
                input(f, small, req.smallExceptionReason(), false, intent, intentReason, false, req.bant()), activeRules());
        List<String> missing = missingFields(f, req);
        if (req.override()) missing = missing.stream().filter(NEEDED_FOR_OVERRIDE::contains).toList();
        boolean can = req.overrideReady() ? missing.isEmpty() : r.canSubmit() && missing.isEmpty();
        return new Checklist(r.results(), missing, can, req.overrideReady() || r.needsApproval(),
                f.stakeholder() == null ? null : f.stakeholder().level(), f.stakeholder() == null ? null : f.stakeholder().connectionType());
    }

    /** M4-3: mandatory fields validated before submit. */
    List<String> missingFields(Facts f, SubmitSql req) {
        List<String> m = new ArrayList<>();
        if (f.stakeholder() == null) m.add("qualifyingStakeholder");
        if (f.account().licensePotential() == null) m.add("licensePotential");
        if (f.account().cityId() == null) m.add("city");
        if (req.businessRelevance() == null) m.add("businessRelevance");
        if (req.bant() == null || !req.bant().complete()) m.add("bant");
        if (req.l1() == null || req.l1().scheduledStart() == null) m.add("l1Booking");
        return m;
    }

    // ------------------------------------------------------------------ submit

    @Transactional
    public Map<String, Object> submit(UUID accountId, SubmitSql req, Actor actor) {
        guard.require(actor, Action.SUBMIT_SQL, accountId);
        Integer open = jdbc.queryForObject("SELECT count(*) FROM crm.sql_submission WHERE account_id = :a AND status IN (:open)",
                new MapSqlParameterSource().addValue("a", accountId).addValue("open", OPEN), Integer.class);
        if (open != null && open > 0) throw new DomainException("SQL_ALREADY_OPEN", "This account already has an SQL waiting for validation");

        Facts f = facts(accountId, req.qualifyingStakeholderId(), req.referringStakeholderId());
        boolean override = req.override();
        if (override && !req.overrideReady()) throw new DomainException("INTENT_REASON_REQUIRED", "Write why this is high intent");
        List<String> missing = missingFields(f, req);
        if (override) missing = missing.stream().filter(NEEDED_FOR_OVERRIDE::contains).toList();
        if (!missing.isEmpty()) throw new DomainException("SQL_MISSING_FIELDS", "Missing: " + String.join(", ", missing));
        IntentRating requested = override ? IntentRating.HIGH : req.intentRating();
        if (requested != null) accounts.setIntent(accountId, requested, req.intentReason());
        IntentRating intent = requested != null ? requested : f.account().intentRating();
        String intentReason = req.intentReason() != null ? req.intentReason() : f.account().intentReason();
        boolean small = f.account().sizeBucket() == SizeBucket.SMALL;
        if (!override && small && (req.smallExceptionReason() == null || req.smallExceptionReason().isBlank())) {
            throw new DomainException("SMALL_REASON_REQUIRED", "A Small account can be submitted only with a reason");
        }
        QualificationEngine.Result r = engine.evaluate(
                input(f, small, req.smallExceptionReason(), false, intent, intentReason, false, req.bant()), activeRules());
        if (!override && !r.canSubmit()) {
            String why = r.results().stream().filter(x -> !x.passed()).map(x -> x.code() + ": " + x.detail())
                    .reduce((a, b) -> a + "; " + b).orElse("");
            throw new DomainException("SQL_CRITERIA_FAILED", why);
        }
        StakeholderView s = f.stakeholder();
        UUID referrer = f.referrer() == null ? null : f.referrer().id();
        Short attempt = jdbc.queryForObject("SELECT coalesce(max(attempt_no), 0) + 1 FROM crm.sql_submission WHERE account_id = :a",
                Map.of("a", accountId), Short.class);
        Bant b = req.bant() == null ? new Bant(null, null, null, null, null, null, null, null) : req.bant();
        UUID id = jdbc.queryForObject("""
                INSERT INTO crm.sql_submission (account_id, attempt_no, status, submitted_by, submitted_at, qualifying_stakeholder_id,
                  referring_stakeholder_id, connection_type, license_potential, size_bucket_code, city_id, location_priority,
                  business_relevance, intent_rating, intent_reason, is_small_exception, small_exception_reason,
                  bant_budget_id, bant_budget_note, bant_authority_id, bant_authority_note, bant_need_id, bant_need_note,
                  bant_timeline_id, bant_timeline_note, high_intent_override)
                VALUES (:a, :n, 'MEETING_BOOKED', :by, :now, :s, :ref, :ct, :lic, :bucket, :city, :prio, :rel, :intent,
                  :intentReason, :small, :smallReason, :bb, :bbn, :ba, :ban, :bn, :bnn, :bt, :btn, :override) RETURNING id""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("n", attempt).addValue("by", actor.userId())
                        .addValue("now", Db.ts(Instant.now(clock)))
                        .addValue("s", s.id()).addValue("ref", referrer).addValue("ct", Db.name(s.connectionType()))
                        .addValue("lic", f.account().licensePotential()).addValue("bucket", Db.name(f.account().sizeBucket()))
                        .addValue("city", f.account().cityId()).addValue("prio", Db.name(f.priority()))
                        .addValue("rel", req.businessRelevance().name()).addValue("intent", Db.name(intent))
                        .addValue("intentReason", intentReason).addValue("small", small)
                        .addValue("smallReason", req.smallExceptionReason())
                        .addValue("bb", b.budgetId()).addValue("bbn", b.budgetNote()).addValue("ba", b.authorityId())
                        .addValue("ban", b.authorityNote()).addValue("bn", b.needId()).addValue("bnn", b.needNote())
                        .addValue("bt", b.timelineId()).addValue("btn", b.timelineNote()).addValue("override", override), UUID.class);
        saveCriteria(id, r);

        // M4-4: booking the L1 is part of submission; the account enters the shared pipeline zone at this moment
        List<UUID> attendees = req.l1().attendeeStakeholderIds() == null || req.l1().attendeeStakeholderIds().isEmpty()
                ? List.of(s.id()) : req.l1().attendeeStakeholderIds();
        UUID meetingId = pipeline.bookMeeting(new PipelineApi.BookMeeting(accountId, pipeline.opportunityId(accountId).orElse(null),
                MeetingLevel.L1, req.l1().alsoCompletesL2(), req.l1().scheduledStart(), req.l1().scheduledEnd(),
                req.l1().meetingLink(), attendees, actor.userId(), f.account().aeOwnerId()));
        accounts.enterPipeline(accountId, Instant.now(clock));
        accounts.setSqlCredit(accountId, s.bdrOwnerId() != null ? s.bdrOwnerId() : actor.userId());
        audit.record("sql_submission", id, AuditApi.Action.CREATE, "status", null, "MEETING_BOOKED", actor.userId());
        if (override) audit.record("sql_submission", id, AuditApi.Action.UPDATE, "high_intent_override", null, intentReason, actor.userId());
        events.publishEvent(new SqlSubmitted(id, accountId, actor.userId()));
        return Map.of("submissionId", id, "meetingId", meetingId, "criteria", r.results(), "needsApproval", override || r.needsApproval(),
                "highIntentOverride", override);
    }

    void saveCriteria(UUID submissionId, QualificationEngine.Result r) {
        jdbc.update("DELETE FROM crm.sql_criterion_result WHERE sql_submission_id = :s", Map.of("s", submissionId));
        for (CriterionResult c : r.results()) {
            jdbc.update("""
                    INSERT INTO crm.sql_criterion_result (sql_submission_id, icp_rule_id, passed, detail)
                    SELECT :s, id, :p, :d FROM crm.icp_rule WHERE code = :c""",
                    new MapSqlParameterSource().addValue("s", submissionId).addValue("p", c.status() == CriterionStatus.PASS)
                            .addValue("d", c.status() + ": " + c.detail()).addValue("c", c.code()));
        }
    }

    // ------------------------------------------------------------------ validate / reject

    record Snapshot(UUID id, UUID accountId, String status, UUID submittedBy, UUID stakeholderId, UUID referrerId,
                    boolean smallException, String smallReason, IntentRating intent, String intentReason,
                    UUID bb, UUID ba, UUID bn, UUID bt, boolean highIntentOverride) {}

    Snapshot snapshot(UUID id) {
        return jdbc.query("""
                SELECT id, account_id, status, submitted_by, qualifying_stakeholder_id, referring_stakeholder_id,
                       is_small_exception, small_exception_reason, intent_rating, intent_reason,
                       bant_budget_id, bant_authority_id, bant_need_id, bant_timeline_id, high_intent_override
                  FROM crm.sql_submission WHERE id = :id""", Map.of("id", id),
                        (rs, i) -> new Snapshot(Db.uuid(rs, "id"), Db.uuid(rs, "account_id"), rs.getString("status"),
                                Db.uuid(rs, "submitted_by"), Db.uuid(rs, "qualifying_stakeholder_id"), Db.uuid(rs, "referring_stakeholder_id"),
                                rs.getBoolean("is_small_exception"), rs.getString("small_exception_reason"),
                                Db.enumOf(IntentRating.class, rs.getString("intent_rating")), rs.getString("intent_reason"),
                                Db.uuid(rs, "bant_budget_id"), Db.uuid(rs, "bant_authority_id"), Db.uuid(rs, "bant_need_id"),
                                Db.uuid(rs, "bant_timeline_id"), rs.getBoolean("high_intent_override"))).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("SQL submission", id));
    }

    public record Validate(boolean approve, UUID rejectionReasonId, String note, boolean confirmIntent, boolean approveSmall) {}

    /** BRD Stage 2 / M4-5 / M4-7 / M4-11: manager validates (with approvals) or rejects with a reason. */
    @Transactional
    public Map<String, Object> validate(UUID submissionId, Validate v, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only the sales manager validates SQLs");
        Snapshot sn = snapshot(submissionId);
        if (!OPEN.contains(sn.status())) throw new DomainException("SQL_NOT_OPEN", "This SQL is already " + sn.status());

        if (!v.approve()) {
            if (v.rejectionReasonId() == null) throw new DomainException("REJECTION_REASON_REQUIRED", "A rejection reason is required");
            String label = config.picklistItem(v.rejectionReasonId()).map(ConfigApi.PicklistItem::label).orElse("Rejected");
            jdbc.update("""
                    UPDATE crm.sql_submission SET status = 'REJECTED_NOT_ICP', rejection_reason_id = :r, rejection_note = :n,
                      validated_by = :by, validated_at = :now, version = version + 1 WHERE id = :id""",
                    new MapSqlParameterSource().addValue("r", v.rejectionReasonId()).addValue("n", v.note())
                            .addValue("now", Db.ts(Instant.now(clock)))
                            .addValue("by", actor.userId()).addValue("id", submissionId));
            pipeline.cancelOpenMeetings(sn.accountId());
            accounts.leavePipeline(sn.accountId());
            tasks.create(new TaskApi.NewTask(sn.accountId(), sn.stakeholderId(), null, null, TaskType.OTHER,
                    "SQL rejected: " + label + (v.note() == null ? "" : " — " + v.note()), Instant.now(clock),
                    sn.submittedBy(), TaskSource.SYSTEM, null, actor.userId()));
            audit.record("sql_submission", submissionId, AuditApi.Action.STATUS, "status", sn.status(), "REJECTED_NOT_ICP", actor.userId());
            events.publishEvent(new SqlRejected(submissionId, sn.accountId(), sn.submittedBy(), label));
            return Map.of("status", "REJECTED_NOT_ICP");
        }

        Facts f = facts(sn.accountId(), sn.stakeholderId(), sn.referrerId());
        Bant b = new Bant(sn.bb(), null, sn.ba(), null, sn.bn(), null, sn.bt(), null);
        QualificationEngine.Result r = engine.evaluate(input(f, sn.smallException(), sn.smallReason(), v.approveSmall(),
                sn.intent(), sn.intentReason(), v.confirmIntent(), b), activeRules());
        if (!sn.highIntentOverride() && !r.fullyQualified()) {   // override SQLs: the manager's approval is the check
            String why = r.results().stream().filter(x -> x.status() != CriterionStatus.PASS)
                    .map(x -> x.code() + ": " + x.detail()).reduce((a, c) -> a + "; " + c).orElse("");
            throw new DomainException("SQL_NOT_QUALIFIED", why);
        }
        if (sn.smallException()) {
            double limit = config.decimal("small_sql_share_limit", 0.20);
            long[] counts = smallShare(sn.submittedBy());
            if (new SmallShareGuard(limit).wouldExceed(counts[0], counts[1])) {
                throw new DomainException("SMALL_SHARE_EXCEEDED", "This BDR's Small SQLs would exceed " + Math.round(limit * 100) + "% this month");
            }
        }
        saveCriteria(submissionId, r);
        UUID opp = pipeline.openOpportunity(sn.accountId(), actor.userId());
        jdbc.update("""
                UPDATE crm.sql_submission SET status = 'QUALIFIED_SQL', validated_by = :by, validated_at = :now, opportunity_id = :o,
                  small_approved_by = CASE WHEN :small THEN CAST(:by AS uuid) END, small_approved_at = CASE WHEN :small THEN CAST(:now AS timestamptz) END,
                  intent_confirmed_by = CASE WHEN :intent THEN CAST(:by AS uuid) END, version = version + 1
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("by", actor.userId()).addValue("o", opp).addValue("now", Db.ts(Instant.now(clock)))
                        .addValue("small", sn.smallException() && v.approveSmall()).addValue("intent", v.confirmIntent())
                        .addValue("id", submissionId));
        audit.record("sql_submission", submissionId, AuditApi.Action.STATUS, "status", sn.status(), "QUALIFIED_SQL", actor.userId());
        events.publishEvent(new SqlValidated(submissionId, sn.accountId(), opp, actor.userId(), sn.submittedBy()));
        return Map.of("status", "QUALIFIED_SQL", "opportunityId", opp);
    }

    /** [approved Small SQLs, all approved SQLs] for a BDR in the current IST month. */
    public long[] smallShare(UUID bdrId) {
        LocalDate first = LocalDate.now(clock.withZone(Ist.ZONE)).withDayOfMonth(1);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT count(*) FILTER (WHERE is_small_exception) AS small, count(*) AS total FROM crm.sql_submission
                 WHERE submitted_by = :b AND status = 'QUALIFIED_SQL' AND validated_at >= :from""",
                new MapSqlParameterSource().addValue("b", bdrId).addValue("from", Db.ts(first.atStartOfDay(Ist.ZONE).toInstant())));
        return new long[]{((Number) row.get("small")).longValue(), ((Number) row.get("total")).longValue()};
    }

    // ------------------------------------------------------------------ AE corrections (M4-9)

    private static final Set<String> CORRECTABLE = Set.of("license_potential", "city_id", "business_relevance",
            "bant_budget_id", "bant_authority_id", "bant_need_id", "bant_timeline_id", "intent_rating");

    public record Correction(String field, String value, String note) {}

    @Transactional
    public List<Map<String, Object>> correct(UUID submissionId, Correction c, Actor actor) {
        Snapshot sn = snapshot(submissionId);
        guard.require(actor, Action.CORRECT_QUALIFICATION, sn.accountId());
        if (!CORRECTABLE.contains(c.field())) throw new DomainException("FIELD_NOT_CORRECTABLE", c.field() + " cannot be corrected");
        String original = jdbc.queryForObject("SELECT CAST(" + c.field() + " AS text) FROM crm.sql_submission WHERE id = :id",
                Map.of("id", submissionId), String.class);
        jdbc.update("""
                INSERT INTO crm.qualification_correction (sql_submission_id, field_name, bdr_value, corrected_value, note, corrected_by)
                VALUES (:s, :f, :o, :v, :n, :by)""",
                new MapSqlParameterSource().addValue("s", submissionId).addValue("f", c.field()).addValue("o", original)
                        .addValue("v", c.value()).addValue("n", c.note()).addValue("by", actor.userId()));
        if (c.field().equals("license_potential")) accounts.correctQualification(sn.accountId(), Integer.valueOf(c.value()), null);
        if (c.field().equals("city_id")) accounts.correctQualification(sn.accountId(), null, UUID.fromString(c.value()));
        audit.record("sql_submission", submissionId, AuditApi.Action.UPDATE, c.field(), original, c.value(), actor.userId());
        return corrections(submissionId);
    }

    public List<Map<String, Object>> corrections(UUID submissionId) {
        return jdbc.queryForList("""
                SELECT qc.field_name AS "field", qc.bdr_value AS "bdrValue", qc.corrected_value AS "correctedValue", qc.note,
                       u.full_name AS "correctedBy", qc.corrected_at AS "correctedAt"
                  FROM crm.qualification_correction qc JOIN crm.app_user u ON u.id = qc.corrected_by
                 WHERE qc.sql_submission_id = :s ORDER BY qc.corrected_at""", Map.of("s", submissionId));
    }

    // ------------------------------------------------------------------ reads

    public Map<String, Object> get(UUID id, Actor actor) {
        Snapshot sn = snapshot(id);
        if (actor.role() == Role.BDR && !actor.userId().equals(sn.submittedBy())) guard.require(actor, Action.VIEW_PIPELINE, sn.accountId());
        if (actor.role() == Role.AE) guard.require(actor, Action.VIEW_PIPELINE, sn.accountId());
        Map<String, Object> out = new LinkedHashMap<>(jdbc.queryForMap("""
                SELECT s.id, s.account_id AS "accountId", a.name AS "accountName", s.attempt_no AS "attempt", s.status,
                       u.full_name AS "submittedBy", s.submitted_at AS "submittedAt", s.license_potential AS "licensePotential",
                       s.size_bucket_code AS "sizeBucket", c.name AS "city", s.location_priority AS "locationPriority",
                       s.business_relevance AS "businessRelevance", s.intent_rating AS "intentRating", s.intent_reason AS "intentReason",
                       s.is_small_exception AS "smallException", s.small_exception_reason AS "smallExceptionReason",
                       s.connection_type AS "connectionType", st.first_name || coalesce(' ' || st.last_name, '') AS "qualifyingStakeholder",
                       st.level AS "qualifyingLevel", rf.first_name || coalesce(' ' || rf.last_name, '') AS "referredBy",
                       rf.level AS "referrerLevel", s.validated_at AS "validatedAt", v.full_name AS "validatedBy",
                       rr.label AS "rejectionReason", s.rejection_note AS "rejectionNote", s.high_intent_override AS "highIntentOverride"
                  FROM crm.sql_submission s JOIN crm.account a ON a.id = s.account_id JOIN crm.app_user u ON u.id = s.submitted_by
                  LEFT JOIN crm.city c ON c.id = s.city_id LEFT JOIN crm.stakeholder st ON st.id = s.qualifying_stakeholder_id
                  LEFT JOIN crm.stakeholder rf ON rf.id = s.referring_stakeholder_id LEFT JOIN crm.app_user v ON v.id = s.validated_by
                  LEFT JOIN crm.picklist_value rr ON rr.id = s.rejection_reason_id WHERE s.id = :id""", Map.of("id", id)));
        out.put("criteria", jdbc.queryForList("""
                SELECT r.code, cr.passed, cr.detail FROM crm.sql_criterion_result cr JOIN crm.icp_rule r ON r.id = cr.icp_rule_id
                 WHERE cr.sql_submission_id = :id ORDER BY r.code""", Map.of("id", id)));
        out.put("corrections", corrections(id));
        return out;
    }

    public List<Map<String, Object>> list(String status, UUID bdrId, Actor actor) {
        UUID who = actor.role() == Role.BDR ? actor.userId() : bdrId;
        return jdbc.queryForList("""
                SELECT s.id, s.account_id AS "accountId", a.name AS "accountName", s.status, u.full_name AS "submittedBy",
                       s.submitted_at AS "submittedAt", s.size_bucket_code AS "sizeBucket", s.location_priority AS "locationPriority",
                       s.is_small_exception AS "smallException", s.intent_rating AS "intentRating"
                  FROM crm.sql_submission s JOIN crm.account a ON a.id = s.account_id JOIN crm.app_user u ON u.id = s.submitted_by
                 WHERE (CAST(:st AS text) IS NULL OR s.status = :st) AND (CAST(:b AS uuid) IS NULL OR s.submitted_by = :b)
                 ORDER BY s.submitted_at DESC NULLS LAST LIMIT 500""",
                new MapSqlParameterSource().addValue("st", status).addValue("b", who));
    }

    // ------------------------------------------------------------------ BRD SQL statuses follow the L1 meeting

    @EventListener
    public void onMeetingOutcome(MeetingOutcomeRecorded e) {
        if (e.level() != MeetingLevel.L1) return;
        String to = switch (e.outcome()) {
            case NO_SHOW -> "NO_SHOW";
            case DISQUALIFIED -> "DISQUALIFIED";
            default -> null;
        };
        if (to == null) return;
        jdbc.update("""
                UPDATE crm.sql_submission SET status = :to WHERE account_id = :a
                   AND status IN ('MEETING_BOOKED','PENDING_QUALIFICATION','PENDING_SENIOR_CONNECTION')""",
                Map.of("to", to, "a", e.accountId()));
    }

    @EventListener
    public void onMeetingBooked(MeetingBooked e) {
        if (e.level() != MeetingLevel.L1) return;
        jdbc.update("UPDATE crm.sql_submission SET status = 'MEETING_BOOKED' WHERE account_id = :a AND status = 'NO_SHOW'",
                Map.of("a", e.accountId()));
    }
}