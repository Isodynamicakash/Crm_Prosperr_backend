package com.prosperr.crm.prospecting.application;

import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.domain.TaskType;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class TaskService implements TaskApi {
    private final NamedParameterJdbcTemplate jdbc;
    public TaskService(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public UUID create(NewTask t) {
        return jdbc.queryForObject("""
                INSERT INTO crm.task (account_id, stakeholder_id, opportunity_id, meeting_id, type, title, due_at, assignee_id,
                                      source, source_activity_id, created_by)
                VALUES (:a, :s, :o, :m, :type, :title, :due, :assignee, :source, :act, :by) RETURNING id""",
                new MapSqlParameterSource().addValue("a", t.accountId()).addValue("s", t.stakeholderId())
                        .addValue("o", t.opportunityId()).addValue("m", t.meetingId()).addValue("type", t.type().name())
                        .addValue("title", t.title()).addValue("due", Db.ts(t.dueAt())).addValue("assignee", t.assigneeId())
                        .addValue("source", t.source().name()).addValue("act", t.sourceActivityId()).addValue("by", t.createdBy()),
                UUID.class);
    }

    @Override
    @Transactional
    public int moveOpen(UUID accountId, UUID fromUser, UUID toUser) {
        return jdbc.update("""
                UPDATE crm.task SET assignee_id = :to WHERE account_id = :a AND status = 'OPEN'
                   AND (CAST(:from AS uuid) IS NULL OR assignee_id = :from)""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("from", fromUser).addValue("to", toUser));
    }

    @Override
    @Transactional
    public int closeOpen(UUID stakeholderId, Set<TaskType> types, UUID by) {
        return jdbc.update("""
                UPDATE crm.task SET status = 'DONE', completed_at = now(), completed_by = :by
                 WHERE stakeholder_id = :s AND status = 'OPEN' AND type IN (:types)""",
                new MapSqlParameterSource().addValue("s", stakeholderId).addValue("by", by)
                        .addValue("types", types.stream().map(Enum::name).toList()));
    }

    @Override
    public boolean hasOpen(UUID accountId, TaskType type) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM crm.task WHERE account_id = :a AND type = :t AND status = 'OPEN'",
                Map.of("a", accountId, "t", type.name()), Integer.class);
        return n != null && n > 0;
    }

    public List<Map<String, Object>> list(UUID assignee, String status, Instant dueBefore) {
        return jdbc.queryForList("""
                SELECT t.id, t.type, t.title, t.due_at AS "dueAt", t.status, t.source, t.account_id AS "accountId",
                       a.name AS "accountName", t.stakeholder_id AS "stakeholderId",
                       s.first_name || coalesce(' ' || s.last_name, '') AS "stakeholderName", t.meeting_id AS "meetingId"
                  FROM crm.task t JOIN crm.account a ON a.id = t.account_id
                  LEFT JOIN crm.stakeholder s ON s.id = t.stakeholder_id
                 WHERE t.assignee_id = :u AND t.status = :st AND (CAST(:before AS timestamptz) IS NULL OR t.due_at < :before)
                 ORDER BY t.due_at""",
                new MapSqlParameterSource().addValue("u", assignee).addValue("st", status == null ? "OPEN" : status)
                        .addValue("before", Db.ts(dueBefore)));
    }

    @Transactional
    public void complete(UUID taskId, Actor actor) {
        UUID assignee = assignee(taskId);
        if (!actor.isLeadership() && !actor.userId().equals(assignee)) throw new ForbiddenException("Not your task");
        jdbc.update("UPDATE crm.task SET status = 'DONE', completed_at = now(), completed_by = :by WHERE id = :id",
                Map.of("id", taskId, "by", actor.userId()));
    }

    @Transactional
    public void reschedule(UUID taskId, Instant dueAt, UUID newAssignee, Actor actor) {
        UUID assignee = assignee(taskId);
        if (!actor.isLeadership() && !actor.userId().equals(assignee)) throw new ForbiddenException("Not your task");
        if (newAssignee != null && !actor.isLeadership()) throw new ForbiddenException("Only managers reassign tasks");
        jdbc.update("""
                UPDATE crm.task SET due_at = coalesce(:due, due_at), assignee_id = coalesce(:to, assignee_id) WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", taskId).addValue("due", Db.ts(dueAt)).addValue("to", newAssignee));
    }

    private UUID assignee(UUID taskId) {
        return jdbc.queryForList("SELECT assignee_id FROM crm.task WHERE id = :id", Map.of("id", taskId), UUID.class)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Task", taskId));
    }
}
