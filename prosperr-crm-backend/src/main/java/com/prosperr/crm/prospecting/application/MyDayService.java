package com.prosperr.crm.prospecting.application;

import com.prosperr.crm.prospecting.domain.MyDayItem;
import com.prosperr.crm.prospecting.domain.MyDayOrdering;
import com.prosperr.crm.shared.domain.Ist;
import com.prosperr.crm.shared.jdbc.Db;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** PRD M2-1: overdue and due-today tasks, then untouched stakeholders, then revival accounts due. */
@Service
@Transactional(readOnly = true)
public class MyDayService {
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public MyDayService(NamedParameterJdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public List<MyDayItem> forUser(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(Ist.ZONE));
        Instant dayStart = today.atStartOfDay(Ist.ZONE).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(Ist.ZONE).toInstant();
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", userId)
                .addValue("start", Db.ts(dayStart)).addValue("end", Db.ts(dayEnd)).addValue("today", today);
        List<MyDayItem> items = new ArrayList<>();

        items.addAll(jdbc.query("""
                SELECT t.id, t.account_id, a.name AS account_name, t.stakeholder_id,
                       s.first_name || coalesce(' ' || s.last_name, '') AS stakeholder_name, s.designation,
                       (SELECT ph.phone_e164 FROM crm.stakeholder_phone ph WHERE ph.stakeholder_id = s.id AND ph.is_valid
                         ORDER BY ph.is_primary DESC LIMIT 1) AS phone,
                       t.title, t.due_at, coalesce(dm.priority_rank, 8) AS rank
                  FROM crm.task t JOIN crm.account a ON a.id = t.account_id
                  LEFT JOIN crm.stakeholder s ON s.id = t.stakeholder_id
                  LEFT JOIN crm.designation_map dm ON dm.id = s.designation_map_id
                 WHERE t.assignee_id = :u AND t.status = 'OPEN' AND t.due_at < :end""", p,
                (rs, i) -> {
                    Instant due = Db.instant(rs, "due_at");
                    return new MyDayItem(due.isBefore(dayStart) ? MyDayItem.Section.OVERDUE : MyDayItem.Section.DUE_TODAY,
                            Db.uuid(rs, "id"), Db.uuid(rs, "account_id"), rs.getString("account_name"),
                            Db.uuid(rs, "stakeholder_id"), rs.getString("stakeholder_name"), rs.getString("designation"),
                            rs.getString("phone"), rs.getString("title"), due, rs.getInt("rank"));
                }));

        items.addAll(jdbc.query("""
                SELECT s.id, s.account_id, a.name AS account_name, s.first_name || coalesce(' ' || s.last_name, '') AS name,
                       s.designation, coalesce(dm.priority_rank, 8) AS rank,
                       (SELECT ph.phone_e164 FROM crm.stakeholder_phone ph WHERE ph.stakeholder_id = s.id AND ph.is_valid
                         ORDER BY ph.is_primary DESC LIMIT 1) AS phone
                  FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                  LEFT JOIN crm.designation_map dm ON dm.id = s.designation_map_id
                 WHERE s.bdr_owner_id = :u AND s.engagement_status = 'NOT_CONTACTED' AND NOT s.do_not_contact
                   AND a.account_status = 'ACTIVE'
                   AND NOT EXISTS (SELECT 1 FROM crm.task t WHERE t.stakeholder_id = s.id AND t.status = 'OPEN')
                 ORDER BY rank, a.name LIMIT 200""", p,
                (rs, i) -> new MyDayItem(MyDayItem.Section.UNTOUCHED, null, Db.uuid(rs, "account_id"),
                        rs.getString("account_name"), Db.uuid(rs, "id"), rs.getString("name"), rs.getString("designation"),
                        rs.getString("phone"), "First call", null, rs.getInt("rank"))));

        items.addAll(jdbc.query("""
                SELECT v.account_id, v.name, v.revisit_on, coalesce(v.size_rank, 0) AS size_rank
                  FROM crm.v_revival_queue v
                 WHERE EXISTS (SELECT 1 FROM crm.account_bdr ab WHERE ab.account_id = v.account_id AND ab.bdr_id = :u)
                   AND NOT EXISTS (SELECT 1 FROM crm.task t WHERE t.account_id = v.account_id AND t.type = 'REVIVAL'
                                   AND t.status = 'OPEN')""", p,
                (rs, i) -> new MyDayItem(MyDayItem.Section.REVIVAL, null, Db.uuid(rs, "account_id"), rs.getString("name"),
                        null, null, null, null, "Revisit: find a new stakeholder", null, 10 - rs.getInt("size_rank"))));
        return MyDayOrdering.sort(items);
    }
}
