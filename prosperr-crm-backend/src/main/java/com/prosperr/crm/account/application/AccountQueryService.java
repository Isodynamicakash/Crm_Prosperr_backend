package com.prosperr.crm.account.application;

import com.prosperr.crm.account.api.StakeholderView;
import com.prosperr.crm.account.domain.AccountEngagement;
import com.prosperr.crm.account.domain.StakeholderPrioritizer;
import com.prosperr.crm.account.domain.StakeholderSnapshot;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.Zone;
import com.prosperr.crm.shared.jdbc.AccountFilter;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.AccessPolicy;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Read models: lists, search, account page, multithreading view and timeline. */
@Service
@Transactional(readOnly = true)
public class AccountQueryService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountService accounts;
    private final StakeholderService stakeholders;
    private final ConfigApi config;
    private final AccessPolicy policy = new AccessPolicy();

    public AccountQueryService(NamedParameterJdbcTemplate jdbc, AccountService accounts, StakeholderService stakeholders,
                               ConfigApi config) {
        this.jdbc = jdbc; this.accounts = accounts; this.stakeholders = stakeholders; this.config = config;
    }



    public record Page(List<Map<String, Object>> items, long total, int page, int size) {}

    /** Active is the default view; status=INACTIVE or ALL for the other views (M8-8). */
    public Page list(AccountFilter f, Actor viewer, int page, int size, String sort) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = f.where(viewer, p);
        String order = switch (sort == null ? "" : sort) {
            case "name" -> "a.name";
            case "lastActivity" -> "a.last_activity_at NULLS FIRST";
            case "nextStepDue" -> "a.next_step_due NULLS FIRST";
            case "myLastTouch" -> "(SELECT max(x.occurred_at) FROM crm.activity x WHERE x.account_id = a.id AND x.logged_by = :viewer) DESC NULLS LAST";
            default -> "a.created_at DESC";
        };
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Map<String, Object>> items = jdbc.queryForList(com.prosperr.crm.shared.jdbc.AccountListSql.COLUMNS + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", p);
        Long total = jdbc.queryForObject("SELECT count(*) FROM crm.account a" + where, p, Long.class);
        return new Page(items, total == null ? 0 : total, page, size);
    }

    /**
     * People who manage the accounts this viewer can see, with how many each one has: AEs by {@code ae_owner_id},
     * BDRs through {@code account_bdr} (sourcer or contributor). Uses the same visibility and filters as the list,
     * so the counts match what the list shows when you pick that person.
     */
    public List<Map<String, Object>> owners(AccountFilter f, Actor viewer) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = f.where(viewer, p);
        String sql = """
            WITH vis AS (SELECT a.id, a.ae_owner_id, a.visibility_zone FROM crm.account a""" + where + """
            )
            SELECT * FROM (
              SELECT u.id AS "userId", u.full_name AS "name", u.role AS "role", u.is_active AS "active",
                     'AE' AS "ownsAs", count(*) AS "accounts",
                     count(*) FILTER (WHERE v.visibility_zone = 'PIPELINE') AS "pipeline"
                FROM vis v JOIN crm.app_user u ON u.id = v.ae_owner_id
               GROUP BY u.id, u.full_name, u.role, u.is_active
              UNION ALL
              SELECT u.id, u.full_name, u.role, u.is_active, 'BDR', count(DISTINCT v.id),
                     count(DISTINCT v.id) FILTER (WHERE v.visibility_zone = 'PIPELINE')
                FROM vis v JOIN crm.account_bdr ab ON ab.account_id = v.id JOIN crm.app_user u ON u.id = ab.bdr_id
               GROUP BY u.id, u.full_name, u.role, u.is_active
            ) o
            ORDER BY CASE "ownsAs" WHEN 'AE' THEN 0 ELSE 1 END, "accounts" DESC, "name"
            """;
        return jdbc.queryForList(sql, p);
    }

    /** M1-6: global search by account, person, email, phone or LinkedIn, respecting visibility. */
    public Map<String, List<Map<String, Object>>> search(String q, Actor viewer) {
        if (q == null || q.isBlank()) return Map.of("accounts", List.of(), "stakeholders", List.of());
        String term = q.trim();
        String digits = term.replaceAll("[^0-9]", "");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("q", "%" + term + "%")
                .addValue("digits", digits.length() >= 6 ? "%" + digits.substring(Math.max(0, digits.length() - 10)) : null)
                .addValue("leader", viewer.isLeadership()).addValue("viewer", viewer.userId());
        String visible = " AND (:leader OR a.visibility_zone = 'PIPELINE' OR EXISTS (SELECT 1 FROM crm.account_bdr vb"
                + " WHERE vb.account_id = a.id AND vb.bdr_id = :viewer))";
        List<Map<String, Object>> acc = jdbc.queryForList("""
                SELECT a.id, a.name, a.domain, a.lifecycle_stage AS "stage", a.account_status AS "status"
                  FROM crm.account a WHERE (a.name ILIKE :q OR a.domain ILIKE :q)""" + visible + " ORDER BY a.name LIMIT 20", p);
        List<Map<String, Object>> people = jdbc.queryForList("""
                SELECT DISTINCT s.id, s.first_name || coalesce(' ' || s.last_name, '') AS "name", s.designation,
                       a.id AS "accountId", a.name AS "accountName"
                  FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                  LEFT JOIN crm.stakeholder_email e ON e.stakeholder_id = s.id
                  LEFT JOIN crm.stakeholder_phone ph ON ph.stakeholder_id = s.id
                 WHERE (s.first_name || ' ' || coalesce(s.last_name, '') ILIKE :q OR e.email_normalized ILIKE :q
                        OR s.linkedin_url ILIKE :q OR (CAST(:digits AS text) IS NOT NULL AND ph.phone_e164 LIKE :digits))"""
                + visible + " LIMIT 20", p);
        return Map.of("accounts", acc, "stakeholders", people);
    }

    /** M1-2 account page: details, owners, stage, status, stakeholders by department, meetings, opportunity, timeline. */
    public Map<String, Object> page(UUID accountId, Actor viewer) {
        accounts.require(viewer, Action.VIEW_PIPELINE, accountId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", accounts.get(accountId));
        // who has already worked this company, so two BDRs don't call the same people blind
        out.put("workedBy", jdbc.queryForList("""
                SELECT u.id AS "userId", u.full_name AS "name", u.role AS "role", u.id = :me AS "me",
                       count(*) FILTER (WHERE x.type = 'CALL') AS "calls", count(*) AS "touches", max(x.occurred_at) AS "lastAt"
                  FROM crm.activity x JOIN crm.app_user u ON u.id = x.logged_by
                 WHERE x.account_id = :a AND u.role IN ('BDR', 'AE')
                 GROUP BY u.id, u.full_name, u.role ORDER BY max(x.occurred_at) DESC""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("me", viewer.userId())));
        out.put("owners", jdbc.queryForList("""
                SELECT 'AE' AS "role", u.id, u.full_name AS "name" FROM crm.account a JOIN crm.app_user u ON u.id = a.ae_owner_id WHERE a.id = :a
                UNION ALL
                SELECT ab.role, u.id, u.full_name FROM crm.account_bdr ab JOIN crm.app_user u ON u.id = ab.bdr_id WHERE ab.account_id = :a""",
                Map.of("a", accountId)));
        Map<Department, List<StakeholderView>> byDept = new EnumMap<>(Department.class);
        stakeholders.forAccount(accountId, viewer).stream().sorted(Comparator.comparingInt(StakeholderView::priorityRank))
                .forEach(s -> byDept.computeIfAbsent(s.department(), k -> new ArrayList<>()).add(s));
        out.put("stakeholdersByDepartment", byDept);
        out.put("meetings", jdbc.queryForList("""
                SELECT m.id, m.level, m.also_completes_l2 AS "alsoCompletesL2", m.status, m.outcome, m.scheduled_start AS "scheduledStart",
                       m.notes, m.next_step AS "nextStep", ae.full_name AS "ae", bk.full_name AS "bookedBy"
                  FROM crm.meeting m LEFT JOIN crm.app_user ae ON ae.id = m.ae_id JOIN crm.app_user bk ON bk.id = m.booked_by
                 WHERE m.account_id = :a ORDER BY m.scheduled_start DESC""", Map.of("a", accountId)));
        out.put("opportunity", jdbc.queryForList("""
                SELECT o.id, o.stage, o.stage_entered_at AS "stageEnteredAt", o.temperature, o.expected_mrr AS "expectedMrr",
                       o.license_count AS "licenseCount", o.next_step AS "nextStep", o.next_step_due AS "nextStepDue",
                       o.expected_close_date AS "expectedCloseDate", o.version
                  FROM crm.opportunity o WHERE o.account_id = :a AND o.is_primary""", Map.of("a", accountId)).stream().findFirst().orElse(null));
        out.put("timeline", timeline(accountId, viewer, null, null, null, null).events().stream().limit(50).toList());
        return out;
    }

    // ------------------------------------------------------------------ multithreading (M7, M2-6, M2-7)

    public record TriedRow(UUID stakeholderId, String type, String outcome, Instant at, String by) {}

    public Map<String, Object> multithreading(UUID accountId, Actor viewer) {
        accounts.require(viewer, Action.VIEW_PIPELINE, accountId);
        List<StakeholderView> list = stakeholders.forAccount(accountId, viewer);
        List<StakeholderSnapshot> snaps = list.stream().filter(s -> !s.masked()).map(s -> new StakeholderSnapshot(s.id(),
                s.fullName(), s.department(), s.level(), s.priorityRank(), s.engagement(), s.doNotContact(),
                s.primarySpoc(), s.decisionMaker())).toList();
        StakeholderPrioritizer prio = new StakeholderPrioritizer();
        AccountEngagement.Assessment assessment = new AccountEngagement().assess(snaps);

        Map<String, List<StakeholderView>> grouped = new LinkedHashMap<>();
        for (Department d : List.of(Department.FINANCE, Department.HR, Department.PAYROLL, Department.OTHER)) {
            grouped.put(d.name(), list.stream().filter(s -> s.department() == d)
                    .sorted(Comparator.comparingInt(StakeholderView::priorityRank)
                            .thenComparing(s -> s.level() == null ? 9 : s.level())).toList());
        }
        List<TriedRow> tried = jdbc.query("""
                SELECT DISTINCT ON (act.stakeholder_id) act.stakeholder_id, act.type, pv.label, act.occurred_at, u.full_name
                  FROM crm.activity act LEFT JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                  JOIN crm.app_user u ON u.id = act.logged_by
                 WHERE act.account_id = :a AND act.stakeholder_id IS NOT NULL
                 ORDER BY act.stakeholder_id, act.occurred_at DESC""", Map.of("a", accountId),
                (rs, i) -> new TriedRow(Db.uuid(rs, "stakeholder_id"), rs.getString("type"), rs.getString("label"),
                        Db.instant(rs, "occurred_at"), rs.getString("full_name")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stakeholdersByFunction", grouped);
        out.put("coverage", assessment.coverage());
        out.put("needsMultithreading", assessment.needsMultithreading());
        out.put("onlyJuniorEngaged", assessment.onlyJuniorEngaged());
        out.put("suggestedNext", prio.suggestNext(snaps).orElse(null));
        out.put("lastTouchByStakeholder", tried);
        return out;
    }

    // ------------------------------------------------------------------ timeline (M7-6..M7-8)

    public record TimelineEvent(String kind, UUID id, UUID stakeholderId, String type, String summary, Instant at,
                                UUID byId, String byName, String zone) {}
    public record Gap(UUID stakeholderId, Instant from, Instant to, long days) {}
    public record Timeline(List<Map<String, Object>> rows, List<TimelineEvent> events, List<Gap> gaps) {}

    public Timeline timeline(UUID accountId, Actor viewer, LocalDate from, LocalDate to, List<String> types, UUID ownerId) {
        accounts.require(viewer, Action.VIEW_PIPELINE, accountId);
        Zone accountZone = accounts.get(accountId).zone();
        boolean historyAfterL1 = config.flag("prospecting_history_visible_after_l1", true);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("a", accountId)
                .addValue("from", from).addValue("to", to).addValue("owner", ownerId);
        List<TimelineEvent> events = new ArrayList<>(jdbc.query("""
                SELECT 'ACTIVITY' AS kind, act.id, act.stakeholder_id, act.type,
                       coalesce(pv.label, '') || CASE WHEN act.remarks IS NULL THEN '' ELSE ' — ' || act.remarks END AS summary,
                       act.occurred_at AS at, act.logged_by AS by_id, u.full_name AS by_name, act.zone_at_time AS zone
                  FROM crm.activity act JOIN crm.app_user u ON u.id = act.logged_by
                  LEFT JOIN crm.picklist_value pv ON pv.id = act.call_outcome_id
                 WHERE act.account_id = :a
                   AND (CAST(:from AS date) IS NULL OR act.occurred_at >= CAST(:from AS date))
                   AND (CAST(:to AS date) IS NULL OR act.occurred_at < CAST(:to AS date) + 1)
                   AND (CAST(:owner AS uuid) IS NULL OR act.logged_by = :owner)
                UNION ALL
                SELECT 'MEETING', m.id, ma.stakeholder_id, 'MEETING',
                       m.level || CASE WHEN m.also_completes_l2 THEN '+L2' ELSE '' END || ' · ' || m.status
                       || coalesce(' · ' || m.outcome, ''), m.scheduled_start, m.booked_by, u.full_name, 'PIPELINE'
                  FROM crm.meeting m JOIN crm.app_user u ON u.id = m.booked_by
                  LEFT JOIN crm.meeting_attendee ma ON ma.meeting_id = m.id
                 WHERE m.account_id = :a
                   AND (CAST(:from AS date) IS NULL OR m.scheduled_start >= CAST(:from AS date))
                   AND (CAST(:to AS date) IS NULL OR m.scheduled_start < CAST(:to AS date) + 1)
                   AND (CAST(:owner AS uuid) IS NULL OR m.booked_by = :owner OR m.ae_id = :owner)
                ORDER BY at DESC""", p,
                (rs, i) -> new TimelineEvent(rs.getString("kind"), Db.uuid(rs, "id"), Db.uuid(rs, "stakeholder_id"),
                        rs.getString("type"), rs.getString("summary"), Db.instant(rs, "at"), Db.uuid(rs, "by_id"),
                        rs.getString("by_name"), rs.getString("zone"))));
        events.removeIf(e -> !policy.canSeeActivity(viewer, Zone.valueOf(e.zone()), e.byId(), accountZone, historyAfterL1));
        if (types != null && !types.isEmpty()) events.removeIf(e -> !types.contains(e.type()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (StakeholderView s : stakeholders.forAccount(accountId, viewer).stream()
                .sorted(Comparator.comparing((StakeholderView s) -> s.department().ordinal())
                        .thenComparingInt(StakeholderView::priorityRank)).toList()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("stakeholderId", s.id());
            r.put("name", s.fullName());
            r.put("department", s.department());
            r.put("designation", s.designation());
            r.put("referredById", s.referredById());
            r.put("engagement", s.engagement());
            rows.add(r);
        }
        int interval = config.stageThreshold(accounts.get(accountId).stage()).followUpIntervalDays();
        List<Gap> gaps = new ArrayList<>();
        Map<UUID, List<Instant>> perStakeholder = new HashMap<>();
        events.stream().filter(e -> e.stakeholderId() != null)
                .forEach(e -> perStakeholder.computeIfAbsent(e.stakeholderId(), k -> new ArrayList<>()).add(e.at()));
        perStakeholder.forEach((sid, times) -> {
            List<Instant> sorted = times.stream().sorted().toList();
            for (int i = 1; i < sorted.size(); i++) {
                long days = Duration.between(sorted.get(i - 1), sorted.get(i)).toDays();
                if (days > interval) gaps.add(new Gap(sid, sorted.get(i - 1), sorted.get(i), days));
            }
        });
        return new Timeline(rows, events, gaps);
    }
}