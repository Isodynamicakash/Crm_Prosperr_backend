package com.prosperr.crm.reporting.application;

import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.PeriodType;
import com.prosperr.crm.shared.domain.TargetMetric;
import com.prosperr.crm.shared.jdbc.Db;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** PRD M9: targets per user per week / month / quarter, achievement computed live. Decision #9: BDR "achieved" = L1 done. */
@Service
public class TargetService {
    private final NamedParameterJdbcTemplate jdbc;
    public TargetService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static LocalDate periodStart(PeriodType type, LocalDate d) {
        return switch (type) {
            case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> d.withDayOfMonth(1);
            case QUARTER -> d.with(IsoFields.DAY_OF_QUARTER, 1);
        };
    }

    public static LocalDate periodEnd(PeriodType type, LocalDate start) {
        return switch (type) {
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
            case QUARTER -> start.plusMonths(3);
        };
    }

    public record SetTarget(UUID userId, PeriodType periodType, LocalDate periodDate, TargetMetric metric, BigDecimal value) {}

    @Transactional
    public void set(SetTarget t, UUID by) {
        if (t.value() == null || t.value().signum() < 0) throw new DomainException("TARGET_VALUE", "Target must be zero or more");
        jdbc.update("""
                INSERT INTO crm.target (user_id, period_type, period_start, metric, target_value, set_by)
                VALUES (:u, :p, :s, :m, :v, :by)
                ON CONFLICT (user_id, period_type, period_start, metric) DO UPDATE SET target_value = EXCLUDED.target_value,
                  set_by = EXCLUDED.set_by""",
                new MapSqlParameterSource().addValue("u", t.userId()).addValue("p", t.periodType().name())
                        .addValue("s", periodStart(t.periodType(), t.periodDate())).addValue("m", t.metric().name())
                        .addValue("v", t.value()).addValue("by", by));
    }

    /** Actual value of a metric for a user between [from, to) in IST. */
    public BigDecimal actual(UUID userId, TargetMetric metric, LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", userId).addValue("from", from).addValue("to", to);
        String sql = switch (metric) {
            case L1_BOOKED -> """
                    SELECT count(*) FROM crm.meeting WHERE level = 'L1' AND booked_by = :u AND rescheduled_from_id IS NULL
                       AND (booked_on AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (booked_on AT TIME ZONE 'Asia/Kolkata')::date < :to""";
            case L1_DONE -> doneSql("L1");
            case L2_DONE -> doneSql("L2");
            case L3_DONE -> doneSql("L3");
            case WON -> """
                    SELECT count(*) FROM crm.opportunity o JOIN crm.account a ON a.id = o.account_id
                     WHERE o.stage IN ('WON','IMPLEMENTATION') AND a.ae_owner_id = :u
                       AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date < :to""";
            case MRR -> """
                    SELECT coalesce(sum(o.expected_mrr), 0) FROM crm.opportunity o JOIN crm.account a ON a.id = o.account_id
                     WHERE o.stage IN ('WON','IMPLEMENTATION') AND a.ae_owner_id = :u
                       AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (o.closed_at AT TIME ZONE 'Asia/Kolkata')::date < :to""";
            case SQL_APPROVED -> """
                    SELECT count(*) FROM crm.sql_submission WHERE submitted_by = :u AND status = 'QUALIFIED_SQL'
                       AND (validated_at AT TIME ZONE 'Asia/Kolkata')::date >= :from AND (validated_at AT TIME ZONE 'Asia/Kolkata')::date < :to""";
        };
        BigDecimal v = jdbc.queryForObject(sql, p, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** "Done" counts for the BDR who booked it or the AE who ran it (L1+L2 combined counts for both levels). */
    private static String doneSql(String level) {
        String levelMatch = level.equals("L2") ? "(m.level = 'L2' OR (m.level = 'L1' AND m.also_completes_l2))" : "m.level = '" + level + "'";
        return "SELECT count(*) FROM crm.meeting m WHERE " + levelMatch + " AND m.status = 'COMPLETED' AND (m.booked_by = :u OR m.ae_id = :u)"
                + " AND (m.scheduled_start AT TIME ZONE 'Asia/Kolkata')::date >= :from"
                + " AND (m.scheduled_start AT TIME ZONE 'Asia/Kolkata')::date < :to";
    }

    public record Achievement(UUID userId, String userName, String metric, BigDecimal target, BigDecimal actual, Double pct) {}

    public List<Achievement> achievement(UUID userId, PeriodType type, LocalDate date) {
        LocalDate start = periodStart(type, date);
        LocalDate end = periodEnd(type, start);
        List<Map<String, Object>> targets = jdbc.queryForList("""
                SELECT t.user_id, u.full_name, t.metric, t.target_value FROM crm.target t JOIN crm.app_user u ON u.id = t.user_id
                 WHERE t.period_type = :p AND t.period_start = :s AND (CAST(:u AS uuid) IS NULL OR t.user_id = :u)
                 ORDER BY u.full_name, t.metric""",
                new MapSqlParameterSource().addValue("p", type.name()).addValue("s", start).addValue("u", userId));
        List<Achievement> out = new ArrayList<>();
        for (var t : targets) {
            UUID u = (UUID) t.get("user_id");
            TargetMetric m = TargetMetric.valueOf((String) t.get("metric"));
            BigDecimal target = (BigDecimal) t.get("target_value");
            BigDecimal actual = actual(u, m, start, end);
            Double pct = target.signum() == 0 ? null : actual.multiply(BigDecimal.valueOf(100)).divide(target, 1, java.math.RoundingMode.HALF_UP).doubleValue();
            out.add(new Achievement(u, (String) t.get("full_name"), m.name(), target, actual, pct));
        }
        return out;
    }
}
