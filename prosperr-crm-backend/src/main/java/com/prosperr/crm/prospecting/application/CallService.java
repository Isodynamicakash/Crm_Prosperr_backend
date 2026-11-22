package com.prosperr.crm.prospecting.application;

import com.prosperr.crm.account.api.*;
import com.prosperr.crm.account.domain.StakeholderPrioritizer;
import com.prosperr.crm.account.domain.StakeholderSnapshot;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.prospecting.api.CallLogged;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.prospecting.domain.OutcomePlan;
import com.prosperr.crm.prospecting.domain.OutcomePlanner;
import com.prosperr.crm.prospecting.domain.OutcomeRule;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BDR calling (PRD M2-2..M2-7): log an outcome in two clicks; the rule decides status and next task. */
@Service
public class CallService {
    private static final LocalTime TASK_TIME = LocalTime.of(10, 0);
    private final NamedParameterJdbcTemplate jdbc;
    private final StakeholderApi stakeholders;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final ActivityApi activities;
    private final TaskApi tasks;
    private final MyDayService myDay;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final OutcomePlanner planner = new OutcomePlanner(WorkingDays.weekdaysOnly());

    public CallService(NamedParameterJdbcTemplate jdbc, StakeholderApi stakeholders, AccountApi accounts, AccountGuard guard,
                       ActivityApi activities, TaskApi tasks, MyDayService myDay, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = jdbc; this.stakeholders = stakeholders; this.accounts = accounts; this.guard = guard;
        this.activities = activities; this.tasks = tasks; this.myDay = myDay; this.events = events; this.clock = clock;
    }

    record LoadedRule(UUID outcomeId, OutcomeRule rule) {}

    LoadedRule rule(String code) {
        return jdbc.query("""
                SELECT p.id, p.code, p.label, r.sets_engagement_status, r.next_task_type, r.next_task_offset_wdays,
                       r.requires_date, r.requires_reason, r.max_attempts, r.after_max_status, r.revisit_days,
                       r.offer_sequence, r.marks_phone_invalid, r.sets_icp_fit_no, r.opens_l1_booking, r.creates_referral
                  FROM crm.call_outcome_rule r JOIN crm.picklist_value p ON p.id = r.outcome_id
                 WHERE p.picklist_key = 'CALL_OUTCOME' AND p.code = :c AND p.is_active""", Map.of("c", code),
                (rs, i) -> new LoadedRule(Db.uuid(rs, "id"), new OutcomeRule(rs.getString("code"), rs.getString("label"),
                        Db.enumOf(EngagementStatus.class, rs.getString("sets_engagement_status")),
                        Db.enumOf(TaskType.class, rs.getString("next_task_type")), Db.integer(rs, "next_task_offset_wdays"),
                        rs.getBoolean("requires_date"), rs.getBoolean("requires_reason"), Db.integer(rs, "max_attempts"),
                        Db.enumOf(EngagementStatus.class, rs.getString("after_max_status")), Db.integer(rs, "revisit_days"),
                        rs.getBoolean("offer_sequence"), rs.getBoolean("marks_phone_invalid"), rs.getBoolean("sets_icp_fit_no"),
                        rs.getBoolean("opens_l1_booking"), rs.getBoolean("creates_referral"))))
                .stream().findFirst().orElseThrow(() -> new NotFoundException("Call outcome", code));
    }

    public record Referral(String fullName, String designation, List<String> emails, List<String> phones, String linkedinUrl) {}

    public record LogCall(String outcomeCode, UUID phoneId, String remark, LocalDate followUpDate, String reason,
                          EngagementStatus overrideStatus, LocalDate overrideTaskDue, Referral referral) {}

    public record LogCallResult(UUID activityId, OutcomePlan plan, UUID taskId, StakeholderView referredStakeholder,
                                boolean offerSequence, boolean openL1Booking, StakeholderSnapshot suggestedNextStakeholder,
                                Object nextQueueItem) {}

    LocalDate today() { return LocalDate.now(clock.withZone(Ist.ZONE)); }

    /** Shown before save; every value can be edited (M2-3). */
    @Transactional(readOnly = true)
    public OutcomePlan preview(UUID stakeholderId, String outcomeCode, LocalDate followUpDate, String reason) {
        StakeholderView s = stakeholders.get(stakeholderId);
        return planner.plan(rule(outcomeCode).rule(), s.callAttempts(), today(), followUpDate, reason);
    }

    @Transactional
    public LogCallResult log(UUID stakeholderId, LogCall req, Actor actor) {
        StakeholderView s = stakeholders.get(stakeholderId);
        AccountView acc = accounts.get(s.accountId());
        guard.require(actor, acc.zone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.LOG_ACTIVITY, s.accountId());
        if (acc.zone() == Zone.PROSPECTING && actor.role() == Role.BDR && s.bdrOwnerId() != null
                && !s.bdrOwnerId().equals(actor.userId())) {
            throw new ForbiddenException("This stakeholder is worked by another BDR");
        }
        if (s.bdrOwnerId() == null && actor.role() == Role.BDR) stakeholders.setBdrOwner(stakeholderId, actor.userId());

        if (req == null || req.outcomeCode() == null || req.outcomeCode().isBlank()) throw new DomainException("OUTCOME_REQUIRED", "Choose a call outcome");
        LoadedRule lr = rule(req.outcomeCode());
        OutcomePlan plan = planner.plan(lr.rule(), s.callAttempts(), today(), req.followUpDate(), req.reason());
        if (req.overrideStatus() != null) plan = plan.withStatus(req.overrideStatus());
        if (req.overrideTaskDue() != null) plan = plan.withTask(plan.taskType() == null ? TaskType.CALL : plan.taskType(), req.overrideTaskDue());

        String remarks = joinRemarks(req.remark(), req.reason());
        UUID activityId = activities.log(new ActivityApi.NewActivity(s.accountId(), stakeholderId, null, null, null,
                req.phoneId(), ActivityType.CALL, lr.outcomeId(), remarks, null, actor.userId()));

        if (plan.markPhoneInvalid() && req.phoneId() != null) stakeholders.markPhoneInvalid(req.phoneId());
        if (plan.setIcpFitNo()) accounts.setIcpFit(s.accountId(), IcpFit.NO);
        if (plan.newStatus() != null) stakeholders.setEngagement(stakeholderId, plan.newStatus(), actor);
        stakeholders.setCallAttempts(stakeholderId, plan.attemptsAfter());
        tasks.closeOpen(stakeholderId, EnumSet.of(TaskType.CALL, TaskType.FOLLOW_UP, TaskType.SEND_TEMPLATE), actor.userId());

        UUID taskId = null;
        if (plan.taskType() != null && plan.taskDue() != null) {
            boolean phonesLeft = stakeholders.get(stakeholderId).phones().stream().anyMatch(StakeholderView.PhoneView::valid);
            if (plan.taskType() != TaskType.FIND_NUMBER || !phonesLeft) {
                Instant due = plan.taskDue().atTime(TASK_TIME).atZone(Ist.ZONE).toInstant();
                taskId = tasks.create(new TaskApi.NewTask(s.accountId(), stakeholderId, null, null, plan.taskType(),
                        title(plan.taskType(), s, acc), due, actor.userId(), TaskSource.CALL_RULE, activityId, actor.userId()));
                stakeholders.setNextFollowUp(stakeholderId, due);
            }
        } else {
            stakeholders.setNextFollowUp(stakeholderId, null);
        }

        StakeholderView referred = null;
        if (plan.createReferral() && req.referral() != null && req.referral().fullName() != null) {
            referred = stakeholders.create(new StakeholderApi.CreateStakeholder(s.accountId(), null, null,
                    req.referral().fullName(), req.referral().designation(), null, null, req.referral().emails(),
                    req.referral().phones(), req.referral().linkedinUrl(), stakeholderId, null,
                    actor.role() == Role.BDR ? actor.userId() : s.bdrOwnerId(), false, false, false), actor);
            tasks.create(new TaskApi.NewTask(s.accountId(), referred.id(), null, null, TaskType.CALL,
                    "Call " + referred.fullName() + " (referred by " + s.fullName() + ")", Instant.now(clock),
                    actor.userId(), TaskSource.CALL_RULE, activityId, actor.userId()));
        }

        StakeholderSnapshot suggestion = null;
        if (plan.newStatus() == EngagementStatus.UNRESPONSIVE || plan.newStatus() == EngagementStatus.NOT_RELEVANT
                || plan.newStatus() == EngagementStatus.REFERRED) {
            suggestion = new StakeholderPrioritizer().suggestNext(stakeholders.forAccount(s.accountId()).stream()
                    .map(x -> new StakeholderSnapshot(x.id(), x.fullName(), x.department(), x.level(), x.priorityRank(),
                            x.engagement(), x.doNotContact(), x.primarySpoc(), x.decisionMaker())).toList()).orElse(null);
        }
        events.publishEvent(new CallLogged(activityId, s.accountId(), stakeholderId, req.outcomeCode(), actor.userId(), Instant.now(clock)));
        Object next = myDay.forUser(actor.userId()).stream()
                .filter(i -> !stakeholderId.equals(i.stakeholderId())).findFirst().orElse(null);
        return new LogCallResult(activityId, plan, taskId, referred, plan.offerSequence(), plan.openL1Booking(), suggestion, next);
    }

    private static String title(TaskType type, StakeholderView s, AccountView a) {
        return switch (type) {
            case FIND_NUMBER -> "Find a working number for " + s.fullName() + " at " + a.name();
            case SEND_TEMPLATE -> "Send details to " + s.fullName() + " at " + a.name();
            default -> "Call " + s.fullName() + " at " + a.name();
        };
    }

    private static String joinRemarks(String remark, String reason) {
        if (reason == null || reason.isBlank()) return remark;
        if (remark == null || remark.isBlank()) return reason;
        return remark + " | Reason: " + reason;
    }
}
