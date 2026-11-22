package com.prosperr.crm.pipeline.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.pipeline.api.MeetingBooked;
import com.prosperr.crm.pipeline.api.MeetingOutcomeRecorded;
import com.prosperr.crm.pipeline.api.PipelineApi;
import com.prosperr.crm.pipeline.domain.MeetingRules;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Meetings L1/L2/L3 (PRD M6-1..M6-3, M6-11) and the PipelineApi for other modules. */
@Service
public class MeetingService implements PipelineApi {
    private final NamedParameterJdbcTemplate jdbc;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final StakeholderApi stakeholders;
    private final ActivityApi activities;
    private final TaskApi tasks;
    private final OpportunityService opportunities;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MeetingRules rules = new MeetingRules();

    public MeetingService(NamedParameterJdbcTemplate jdbc, AccountApi accounts, AccountGuard guard, StakeholderApi stakeholders,
                          ActivityApi activities, TaskApi tasks, OpportunityService opportunities,
                          ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc; this.accounts = accounts; this.guard = guard; this.stakeholders = stakeholders;
        this.activities = activities; this.tasks = tasks; this.opportunities = opportunities; this.events = events; this.clock = clock;
    }

    public record Meeting(UUID id, UUID accountId, UUID opportunityId, MeetingLevel level, boolean alsoCompletesL2,
                          MeetingStatus status, MeetingOutcome outcome, UUID bookedBy, UUID aeId, Instant scheduledStart,
                          Instant scheduledEnd, String notes, String nextStep, LocalDate nextStepDue) {}

    Meeting load(UUID id) {
        return jdbc.query("""
                SELECT id, account_id, opportunity_id, level, also_completes_l2, status, outcome, booked_by, ae_id,
                       scheduled_start, scheduled_end, notes, next_step, next_step_due FROM crm.meeting WHERE id = :id""",
                        Map.of("id", id), (rs, i) -> new Meeting(Db.uuid(rs, "id"), Db.uuid(rs, "account_id"), Db.uuid(rs, "opportunity_id"),
                                MeetingLevel.valueOf(rs.getString("level")), rs.getBoolean("also_completes_l2"),
                                MeetingStatus.valueOf(rs.getString("status")), Db.enumOf(MeetingOutcome.class, rs.getString("outcome")),
                                Db.uuid(rs, "booked_by"), Db.uuid(rs, "ae_id"), Db.instant(rs, "scheduled_start"),
                                Db.instant(rs, "scheduled_end"), rs.getString("notes"), rs.getString("next_step"), Db.date(rs, "next_step_due")))
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Meeting", id));
    }

    // ------------------------------------------------------------------ PipelineApi

    @Override
    @Transactional
    public UUID bookMeeting(BookMeeting c) {
        rules.checkCombined(c.level(), c.alsoCompletesL2());
        if (c.scheduledStart() == null) throw new DomainException("TIME_REQUIRED", "Meeting date and time are required");
        // A new booking must be in the future (5 minutes of slack for clock drift between browser and server).
        if (c.scheduledStart().isBefore(Instant.now(clock).minus(java.time.Duration.ofMinutes(5)))) {
            throw new DomainException("TIME_IN_PAST", "Meeting time is in the past. Pick a future date and time");
        }
        if (c.scheduledEnd() != null && !c.scheduledEnd().isAfter(c.scheduledStart())) {
            throw new DomainException("END_BEFORE_START", "Meeting end must be after the start");
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO crm.meeting (account_id, opportunity_id, level, also_completes_l2, booked_by, ae_id, scheduled_start,
                                         scheduled_end, meeting_link)
                VALUES (:a, :o, :l, :combined, :by, :ae, :start, :end, :link) RETURNING id""",
                new MapSqlParameterSource().addValue("a", c.accountId()).addValue("o", c.opportunityId())
                        .addValue("l", c.level().name()).addValue("combined", c.alsoCompletesL2()).addValue("by", c.bookedBy())
                        .addValue("ae", c.aeId()).addValue("start", Db.ts(c.scheduledStart())).addValue("end", Db.ts(c.scheduledEnd()))
                        .addValue("link", c.meetingLink()), UUID.class);
        if (c.attendeeStakeholderIds() != null) {
            for (UUID s : c.attendeeStakeholderIds()) {
                jdbc.update("INSERT INTO crm.meeting_attendee (meeting_id, stakeholder_id) VALUES (:m, :s) ON CONFLICT DO NOTHING",
                        Map.of("m", id, "s", s));
                stakeholders.setEngagement(s, EngagementStatus.MEETING_SCHEDULED, null);
            }
        }
        events.publishEvent(new MeetingBooked(id, c.accountId(), c.level(), c.bookedBy(), c.aeId(), c.scheduledStart()));
        return id;
    }

    @Override
    @Transactional
    public UUID openOpportunity(UUID accountId, UUID actor) {
        Optional<UUID> existing = opportunityId(accountId);
        if (existing.isPresent()) return existing.get();
        new com.prosperr.crm.shared.jdbc.DbSession(jdbc.getJdbcTemplate()).actAs(actor);
        UUID id = jdbc.queryForObject("INSERT INTO crm.opportunity (account_id, stage) VALUES (:a, 'SQL') RETURNING id",
                Map.of("a", accountId), UUID.class);
        jdbc.update("UPDATE crm.meeting SET opportunity_id = :o WHERE account_id = :a AND opportunity_id IS NULL",
                Map.of("o", id, "a", accountId));
        opportunities.reevaluate(id, actor);
        return id;
    }

    @Override
    public Optional<UUID> opportunityId(UUID accountId) {
        return jdbc.queryForList("SELECT id FROM crm.opportunity WHERE account_id = :a AND is_primary",
                Map.of("a", accountId), UUID.class).stream().findFirst();
    }

    @Override
    @Transactional
    public void setMeetingAe(UUID accountId, UUID aeId) {
        jdbc.update("UPDATE crm.meeting SET ae_id = :ae WHERE account_id = :a AND outcome IS NULL AND status IN ('SCHEDULED','RESCHEDULED')",
                Map.of("ae", aeId, "a", accountId));
    }

    @Override
    @Transactional
    public void cancelOpenMeetings(UUID accountId) {
        jdbc.update("UPDATE crm.meeting SET status = 'CANCELLED' WHERE account_id = :a AND status = 'SCHEDULED'", Map.of("a", accountId));
    }

    @Override
    @Transactional
    public void setStage(UUID accountId, LifecycleStage target, UUID actor) {
        opportunityId(accountId).ifPresent(id -> {
            OpportunityService.Opp o = opportunities.load(id);
            opportunities.writeStage(o, target, actor);
        });
    }

    // ------------------------------------------------------------------ AE actions

    public record BookRequest(MeetingLevel level, boolean alsoCompletesL2, Instant scheduledStart, Instant scheduledEnd,
                              String meetingLink, List<UUID> attendeeStakeholderIds) {}

    /** L2 / L3 / other meetings booked by the AE (L1 is booked through SQL submission). */
    @Transactional
    public Meeting book(UUID accountId, BookRequest r, Actor actor) {
        guard.require(actor, Action.UPDATE_MEETINGS, accountId);
        UUID opp = opportunityId(accountId).orElse(null);
        UUID ae = accounts.get(accountId).aeOwnerId();
        return load(bookMeeting(new BookMeeting(accountId, opp, r.level(), r.alsoCompletesL2(), r.scheduledStart(),
                r.scheduledEnd(), r.meetingLink(), r.attendeeStakeholderIds(), actor.userId(), ae)));
    }

    public record OutcomeRequest(MeetingOutcome outcome, String notes, String nextStep, LocalDate nextStepDue,
                                 List<UUID> attendedStakeholderIds) {}

    @Transactional
    public Meeting recordOutcome(UUID meetingId, OutcomeRequest r, Actor actor) {
        Meeting m = load(meetingId);
        guard.require(actor, Action.UPDATE_MEETINGS, m.accountId());
        if (r.outcome() == null) throw new DomainException("OUTCOME_REQUIRED", "Choose an outcome");
        if (r.outcome() != MeetingOutcome.RESCHEDULED && m.scheduledStart() != null && Instant.now(clock).isBefore(m.scheduledStart())) {
            throw new DomainException("MEETING_NOT_STARTED", "This meeting hasn't happened yet. Record the outcome after it starts, or reschedule it.");
        }
        MeetingStatus status = rules.statusFor(r.outcome());
        jdbc.update("""
                UPDATE crm.meeting SET outcome = :o, status = :s, notes = coalesce(:notes, notes), next_step = :ns,
                  next_step_due = :nd, outcome_recorded_by = :by, outcome_recorded_at = now(), version = version + 1
                WHERE id = :id""",
                new MapSqlParameterSource().addValue("o", r.outcome().name()).addValue("s", status.name())
                        .addValue("notes", r.notes()).addValue("ns", r.nextStep()).addValue("nd", r.nextStepDue())
                        .addValue("by", actor.userId()).addValue("id", meetingId));
        List<UUID> attendees = jdbc.queryForList("SELECT stakeholder_id FROM crm.meeting_attendee WHERE meeting_id = :m",
                Map.of("m", meetingId), UUID.class);
        if (r.attendedStakeholderIds() != null) {
            for (UUID s : attendees) {
                jdbc.update("UPDATE crm.meeting_attendee SET attended = :a WHERE meeting_id = :m AND stakeholder_id = :s",
                        Map.of("a", r.attendedStakeholderIds().contains(s), "m", meetingId, "s", s));
            }
        }
        if (status == MeetingStatus.COMPLETED) {
            for (UUID s : attendees) stakeholders.setEngagement(s, EngagementStatus.MEETING_COMPLETED, actor);
        }
        if (r.outcome() == MeetingOutcome.NO_SHOW) {
            // M6-3: No Show creates a reschedule task for the BDR who booked it
            tasks.create(new TaskApi.NewTask(m.accountId(), attendees.isEmpty() ? null : attendees.get(0), m.opportunityId(),
                    meetingId, TaskType.RESCHEDULE_MEETING, "Reschedule the " + m.level() + " meeting (no show)",
                    Instant.now(clock), m.bookedBy(), TaskSource.SYSTEM, null, actor.userId()));
        }
        if (r.nextStep() != null) accounts.setNextStep(m.accountId(), r.nextStep(), r.nextStepDue());
        activities.log(new ActivityApi.NewActivity(m.accountId(), attendees.isEmpty() ? null : attendees.get(0),
                m.opportunityId(), meetingId, null, null, ActivityType.MEETING, null,
                m.level() + " " + r.outcome() + (r.notes() == null ? "" : " — " + r.notes()), null, actor.userId()));
        if (m.opportunityId() != null) opportunities.reevaluate(m.opportunityId(), actor.userId());
        events.publishEvent(new MeetingOutcomeRecorded(meetingId, m.accountId(), m.level(), r.outcome(), m.aeId(), m.bookedBy()));
        return load(meetingId);
    }

    public record RescheduleRequest(Instant scheduledStart, Instant scheduledEnd, String meetingLink) {}

    @Transactional
    public Meeting reschedule(UUID meetingId, RescheduleRequest r, Actor actor) {
        Meeting m = load(meetingId);
        boolean booker = actor.userId().equals(m.bookedBy());
        if (!booker && !guard.can(actor, Action.UPDATE_MEETINGS, m.accountId())) throw new ForbiddenException("Cannot reschedule");
        if (m.status() == MeetingStatus.SCHEDULED) {
            jdbc.update("UPDATE crm.meeting SET status = 'RESCHEDULED', outcome = 'RESCHEDULED' WHERE id = :id", Map.of("id", meetingId));
        }
        List<UUID> attendees = jdbc.queryForList("SELECT stakeholder_id FROM crm.meeting_attendee WHERE meeting_id = :m",
                Map.of("m", meetingId), UUID.class);
        UUID id = bookMeeting(new BookMeeting(m.accountId(), m.opportunityId(), m.level(), m.alsoCompletesL2(), r.scheduledStart(),
                r.scheduledEnd(), r.meetingLink(), attendees, actor.userId(), m.aeId()));
        jdbc.update("UPDATE crm.meeting SET rescheduled_from_id = :old WHERE id = :id", Map.of("old", meetingId, "id", id));
        jdbc.update("UPDATE crm.task SET status = 'DONE', completed_at = now(), completed_by = :by WHERE meeting_id = :m AND type = 'RESCHEDULE_MEETING' AND status = 'OPEN'",
                Map.of("by", actor.userId(), "m", meetingId));
        return load(id);
    }

    /** M6-2: meetings whose time has passed with no outcome. */
    public List<Map<String, Object>> overdueOutcomes(UUID aeId) {
        return jdbc.queryForList("""
                SELECT m.id, m.level, m.scheduled_start AS "scheduledStart", a.id AS "accountId", a.name AS "accountName"
                  FROM crm.meeting m JOIN crm.account a ON a.id = m.account_id
                 WHERE m.outcome IS NULL AND m.status IN ('SCHEDULED','RESCHEDULED') AND m.scheduled_start < now()
                   AND (CAST(:ae AS uuid) IS NULL OR m.ae_id = :ae)
                 ORDER BY m.scheduled_start""", new MapSqlParameterSource().addValue("ae", aeId));
    }

    public List<Map<String, Object>> forAccount(UUID accountId, Actor actor) {
        guard.require(actor, Action.VIEW_PIPELINE, accountId);
        return jdbc.queryForList("""
                SELECT m.id, m.level, m.also_completes_l2 AS "alsoCompletesL2", m.status, m.outcome, m.booked_on AS "bookedOn",
                       bk.full_name AS "bookedBy", ae.full_name AS "ae", m.scheduled_start AS "scheduledStart",
                       m.scheduled_end AS "scheduledEnd", m.meeting_link AS "meetingLink", m.notes, m.next_step AS "nextStep",
                       m.next_step_due AS "nextStepDue", m.rescheduled_from_id AS "rescheduledFromId",
                       (SELECT string_agg(s.first_name || coalesce(' ' || s.last_name, ''), ', ')
                          FROM crm.meeting_attendee ma JOIN crm.stakeholder s ON s.id = ma.stakeholder_id
                         WHERE ma.meeting_id = m.id) AS "attendees"
                  FROM crm.meeting m JOIN crm.app_user bk ON bk.id = m.booked_by LEFT JOIN crm.app_user ae ON ae.id = m.ae_id
                 WHERE m.account_id = :a ORDER BY m.scheduled_start DESC""", Map.of("a", accountId));
    }
}