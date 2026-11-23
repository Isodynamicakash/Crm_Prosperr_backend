package com.prosperr.crm.assignment.application;

import com.prosperr.crm.assignment.domain.AeLoad;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.shared.domain.AssignmentMethod;
import com.prosperr.crm.shared.domain.DomainException;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Handing a person's book to the rest of the team when they leave or change role (PRD M5-5).
 * <p>
 * Plan: every account the person owns gets a proposed new owner of the same role. Strategies:
 * <ul>
 *   <li>BALANCED (default): biggest deals first, each to whoever is least loaded at that moment. AEs are loaded by
 *       weighted pipeline (L2 / L3 / Negotiation count 1.5, other active deals 1, inactive 0.2); BDRs by the active
 *       accounts they are on (prospects 1, pipeline 0.5, inactive 0.2). Ties go to whoever was handed work least recently.</li>
 *   <li>ROUND_ROBIN: in turn, regardless of load.</li>
 *   <li>ONE: everything to one named person.</li>
 * </ul>
 * Apply: moves ownership, contacts, open tasks, upcoming meetings and SQL credit; the leaver's sequences are paused and
 * handed over by the outreach module (AccountAssigned event). Every move is in the assignment history with "User left".
 */
@Service
public class HandoverService {
    private final NamedParameterJdbcTemplate jdbc;
    private final AssignmentService assignment;
    private final UserDirectory users;

    private final org.springframework.context.ApplicationEventPublisher events;

    public HandoverService(NamedParameterJdbcTemplate jdbc, AssignmentService assignment, UserDirectory users,
                           org.springframework.context.ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.assignment = assignment; this.users = users; this.events = events;
    }

    public record Item(UUID accountId, String name, String stage, String status, String ownership, double weight) {}
    public record Move(UUID accountId, UUID toUserId) {}

    /** Accounts the person owns, as AE (ae_owner_id) or BDR (on the account team). */
    List<Item> book(UUID userId, Role role) {
        String sql = role == Role.AE ? """
                SELECT a.id, a.name, a.lifecycle_stage, a.account_status FROM crm.account a WHERE a.ae_owner_id = :u ORDER BY a.name"""
                : """
                SELECT a.id, a.name, a.lifecycle_stage, a.account_status FROM crm.account a
                  JOIN crm.account_bdr ab ON ab.account_id = a.id WHERE ab.bdr_id = :u ORDER BY a.name""";
        return jdbc.query(sql, Map.of("u", userId), (rs, i) -> {
            String stage = rs.getString(3), status = rs.getString(4);
            return new Item(Db.uuid(rs, "id"), rs.getString(2), stage, status, role == Role.AE ? "AE" : "BDR", weight(role, stage, status));
        });
    }

    static double weight(Role role, String stage, String status) {
        if ("INACTIVE".equals(status) || "WON".equals(stage) || "LOST".equals(stage)) return 0.2;
        if (role == Role.AE) return Set.of("L2", "L3", "NEGOTIATION").contains(stage) ? 1.5 : 1.0;
        return "PROSPECT".equals(stage) ? 1.0 : 0.5;
    }

    record Receiver(UUID id, String name, double load, Instant lastHandedAt) {}

    List<Receiver> receivers(Role role, UUID exclude) {
        if (role == Role.AE) {
            Map<UUID, AeLoad> byId = new HashMap<>();
            for (AeLoad l : assignment.loads()) byId.put(l.aeId(), l);
            return users.activeByRole(Role.AE).stream().filter(u -> !u.id().equals(exclude))
                    .map(u -> { AeLoad l = byId.get(u.id());
                        return new Receiver(u.id(), u.fullName(), l == null ? 0 : l.weightedLoad(), l == null ? null : l.lastAssignedAt()); })
                    .toList();
        }
        List<Receiver> out = new ArrayList<>();
        for (UserView u : users.activeByRole(Role.BDR)) {
            if (u.id().equals(exclude)) continue;
            Map<String, Object> r = jdbc.queryForMap("""
                    SELECT coalesce(sum(CASE WHEN a.account_status = 'INACTIVE' OR a.lifecycle_stage IN ('WON','LOST') THEN 0.2
                                             WHEN a.lifecycle_stage = 'PROSPECT' THEN 1 ELSE 0.5 END), 0) AS load,
                           (SELECT max(h.assigned_at) FROM crm.assignment_history h WHERE h.new_owner_id = :u AND h.owner_type = 'BDR') AS last
                      FROM crm.account_bdr ab JOIN crm.account a ON a.id = ab.account_id WHERE ab.bdr_id = :u""", Map.of("u", u.id()));
            out.add(new Receiver(u.id(), u.fullName(), ((Number) r.get("load")).doubleValue(),
                    r.get("last") == null ? null : ((java.sql.Timestamp) r.get("last")).toInstant()));
        }
        return out;
    }

    /** Proposed new owner for every account, plus each receiver's load before and after. Saves nothing. */
    public Map<String, Object> plan(UUID fromUserId, String strategy, UUID toUserId, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only a manager or admin hands over accounts");
        UserView from = users.get(fromUserId);
        if (from.role() != Role.AE && from.role() != Role.BDR) throw new DomainException("NOTHING_TO_HAND_OVER", "Only AEs and BDRs own accounts");
        List<Item> items = new ArrayList<>(book(fromUserId, from.role()));
        List<Receiver> recv = receivers(from.role(), fromUserId);
        if (recv.isEmpty()) throw new DomainException("NO_RECEIVERS", "There is no other active " + from.role() + " to hand over to");
        String s = strategy == null ? "BALANCED" : strategy.toUpperCase(Locale.ROOT);
        if (s.equals("ONE") && recv.stream().noneMatch(r -> r.id().equals(toUserId)))
            throw new DomainException("BAD_RECEIVER", "Pick an active " + from.role() + " to receive everything");

        Map<UUID, Double> load = new LinkedHashMap<>();
        Map<UUID, Instant> last = new HashMap<>();
        Map<UUID, Integer> added = new HashMap<>();
        for (Receiver r : recv) { load.put(r.id(), r.load()); last.put(r.id(), r.lastHandedAt()); added.put(r.id(), 0); }
        Map<UUID, Double> before = new LinkedHashMap<>(load);
        // biggest deals first so they spread before the small ones fill the gaps
        items.sort(Comparator.comparingDouble(Item::weight).reversed().thenComparing(Item::name));
        List<Map<String, Object>> moves = new ArrayList<>();
        int turn = 0;
        List<UUID> order = recv.stream().sorted(Comparator.comparing(Receiver::lastHandedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Receiver::name)).map(Receiver::id).toList();
        for (Item it : items) {
            UUID to = switch (s) {
                case "ONE" -> toUserId;
                case "ROUND_ROBIN" -> order.get(turn++ % order.size());
                default -> load.entrySet().stream().min(Comparator.<Map.Entry<UUID, Double>>comparingDouble(Map.Entry::getValue)
                        .thenComparing(e -> last.get(e.getKey()), Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(e -> added.get(e.getKey()))).orElseThrow().getKey();
            };
            load.merge(to, it.weight(), Double::sum);
            added.merge(to, 1, Integer::sum);
            last.put(to, Instant.now());                       // just handed work: goes to the back on a tie
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", it.accountId()); m.put("name", it.name()); m.put("stage", it.stage()); m.put("status", it.status());
            m.put("weight", it.weight()); m.put("toUserId", to);
            moves.add(m);
        }
        moves.sort(Comparator.comparing(m -> (String) m.get("name")));
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Receiver r : recv) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", r.id()); m.put("name", r.name()); m.put("loadBefore", round(before.get(r.id())));
            m.put("accountsAdded", added.get(r.id())); m.put("loadAfter", round(load.get(r.id())));
            summary.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", Map.of("id", from.id(), "name", from.fullName(), "role", from.role().name(), "active", from.active()));
        out.put("strategy", s);
        out.put("moves", moves);
        out.put("receivers", summary);
        out.put("activeSequences", jdbc.queryForObject("""
                SELECT count(*) FROM crm.sequence_enrollment WHERE enrolled_by = :u AND status IN ('RUNNING','PAUSED')""",
                Map.of("u", fromUserId), Integer.class));
        out.put("openTasks", jdbc.queryForObject("SELECT count(*) FROM crm.task WHERE assignee_id = :u AND status = 'OPEN'",
                Map.of("u", fromUserId), Integer.class));
        return out;
    }

    private static double round(double v) { return Math.round(v * 10) / 10.0; }

    /** Applies the (possibly edited) plan. Each account is moved in the same transaction; returns how many moved. */
    @Transactional
    public Map<String, Object> apply(UUID fromUserId, List<Move> moves, Actor actor) {
        if (!actor.isLeadership()) throw new ForbiddenException("Only a manager or admin hands over accounts");
        UserView from = users.get(fromUserId);
        Set<UUID> owned = new HashSet<>();
        for (Item it : book(fromUserId, from.role())) owned.add(it.accountId());
        UUID reason = jdbc.queryForList("SELECT id FROM crm.picklist_value WHERE picklist_key = 'REASSIGNMENT_REASON' AND code = 'USER_LEFT'",
                Map.of(), UUID.class).stream().findFirst().orElse(null);
        int moved = 0;
        for (Move m : moves) {
            if (!owned.contains(m.accountId())) continue;          // already moved, or not theirs
            UserView to = users.get(m.toUserId());
            if (to.role() != from.role() || !to.active()) throw new DomainException("BAD_RECEIVER", to.fullName() + " is not an active " + from.role());
            if (from.role() == Role.AE) {
                assignment.assign(m.accountId(), new AssignmentService.Assign(to.id(), AssignmentMethod.USER_DEACTIVATED, reason,
                        from.active() ? "Handover from " + from.fullName() : from.fullName() + " left"), actor);
            } else {
                assignment.bulkReassign(fromUserId, List.of(new AssignmentService.BulkMove(m.accountId(), to.id())), actor);
                // the receiver joins the account team in the leaver's place, so it shows under their "Mine"
                jdbc.update("""
                        INSERT INTO crm.account_bdr (account_id, bdr_id, role)
                        SELECT account_id, :to, role FROM crm.account_bdr WHERE account_id = :a AND bdr_id = :from
                        ON CONFLICT (account_id, bdr_id) DO NOTHING""",
                        new MapSqlParameterSource().addValue("to", to.id()).addValue("a", m.accountId()).addValue("from", fromUserId));
                jdbc.update("DELETE FROM crm.account_bdr WHERE account_id = :a AND bdr_id = :from",
                        Map.of("a", m.accountId(), "from", fromUserId));
                jdbc.update("""
                        UPDATE crm.meeting SET booked_by = :to WHERE account_id = :a AND booked_by = :from
                           AND status IN ('SCHEDULED','RESCHEDULED') AND scheduled_start > now()""",
                        Map.of("to", to.id(), "a", m.accountId(), "from", fromUserId));
                events.publishEvent(new com.prosperr.crm.assignment.api.AccountAssigned(m.accountId(), to.id(), fromUserId, "BDR", actor.userId()));
            }
            moved++;
        }
        return Map.of("moved", moved, "remaining", book(fromUserId, from.role()).size());
    }
}
