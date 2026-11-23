package com.prosperr.crm.outreach.application;

import com.prosperr.crm.account.api.*;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.identity.api.UserDirectory;
import com.prosperr.crm.identity.api.UserView;
import com.prosperr.crm.outreach.api.MailGateway;
import com.prosperr.crm.outreach.api.OutreachEvents;
import com.prosperr.crm.outreach.domain.MergeContext;
import com.prosperr.crm.outreach.domain.SendWindow;
import com.prosperr.crm.outreach.domain.SequenceRules;
import com.prosperr.crm.outreach.domain.TemplateRenderer;
import com.prosperr.crm.pipeline.api.MeetingBooked;
import com.prosperr.crm.prospecting.api.ActivityApi;
import com.prosperr.crm.prospecting.api.TaskApi;
import com.prosperr.crm.shared.config.CrmProperties;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.jdbc.Db;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** PRD M3: email sequences sent from each BDR's own mailbox, checked and logged inside the CRM. */
@Service
public class SequenceService {
    private final NamedParameterJdbcTemplate jdbc;
    private final StakeholderApi stakeholders;
    private final AccountApi accounts;
    private final AccountGuard guard;
    private final ActivityApi activities;
    private final TaskApi tasks;
    private final UserDirectory users;
    private final ConfigApi config;
    private final TemplateService templates;
    private final MailGateway mail;
    private final ApplicationEventPublisher events;
    private final CrmProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final SequenceRules rules = new SequenceRules();

    public SequenceService(NamedParameterJdbcTemplate jdbc, StakeholderApi stakeholders, AccountApi accounts, AccountGuard guard,
                           ActivityApi activities, TaskApi tasks, UserDirectory users, ConfigApi config, TemplateService templates,
                           MailGateway mail, ApplicationEventPublisher events, CrmProperties props,
                           PlatformTransactionManager txm, Clock clock) {
        this.jdbc = jdbc; this.stakeholders = stakeholders; this.accounts = accounts; this.guard = guard; this.activities = activities;
        this.tasks = tasks; this.users = users; this.config = config; this.templates = templates; this.mail = mail;
        this.events = events; this.props = props; this.tx = new TransactionTemplate(txm); this.clock = clock;
    }

    SendWindow window() {
        // admin setting send_window: {"days":[1..5],"start":"09:30","end":"18:30","tz":"Asia/Kolkata"}
        return config.json("send_window").map(s -> {
            try {
                var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(s);
                Set<DayOfWeek> days = new HashSet<>();
                node.get("days").forEach(d -> days.add(DayOfWeek.of(d.asInt())));
                return new SendWindow(days, LocalTime.parse(node.get("start").asText()), LocalTime.parse(node.get("end").asText()),
                        ZoneId.of(node.get("tz").asText()));
            } catch (Exception e) {
                return SendWindow.defaultIst();
            }
        }).orElse(SendWindow.defaultIst());
    }

    // ------------------------------------------------------------------ mailbox (M3-1)

    @Transactional
    public void connectMailbox(UUID userId, String gmailAddress, Integer dailyCap) {
        jdbc.update("""
                INSERT INTO crm.user_mailbox (user_id, gmail_address, daily_send_cap) VALUES (:u, :g, coalesce(:c, 80))
                ON CONFLICT (user_id) DO UPDATE SET gmail_address = EXCLUDED.gmail_address,
                  daily_send_cap = EXCLUDED.daily_send_cap, status = 'CONNECTED', updated_at = now()""",
                new MapSqlParameterSource().addValue("u", userId).addValue("g", gmailAddress.toLowerCase()).addValue("c", dailyCap));
    }

    record Mailbox(String address, int cap, int gapSeconds, String status) {}

    Optional<Mailbox> mailbox(UUID userId) {
        return jdbc.query("SELECT gmail_address, daily_send_cap, min_gap_seconds, status FROM crm.user_mailbox WHERE user_id = :u",
                Map.of("u", userId), (rs, i) -> new Mailbox(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4))).stream().findFirst();
    }

    // ------------------------------------------------------------------ start / stop (M3-2, M3-7, M3-11)

    public record StartResult(UUID stakeholderId, String result, UUID enrollmentId, String warning) {}

    @Transactional
    public List<StartResult> start(List<UUID> stakeholderIds, UUID sequenceId, boolean startedFromCall, Actor actor) {
        Mailbox box = mailbox(actor.userId()).filter(m -> m.status().equals("CONNECTED"))
                .orElseThrow(() -> new DomainException("MAILBOX_NOT_CONNECTED", "Connect your Gmail before starting a sequence"));
        UUID seq = sequenceId != null ? sequenceId : jdbc.queryForObject(
                "SELECT id FROM crm.email_sequence WHERE is_default AND is_active", Map.of(), UUID.class);
        Instant first = window().nextOpen(Instant.now(clock));
        List<StartResult> out = new ArrayList<>();
        for (UUID sid : stakeholderIds) {
            try {
                StakeholderView s = stakeholders.get(sid);
                AccountView a = accounts.get(s.accountId());
                guard.require(actor, a.zone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.LOG_ACTIVITY, s.accountId());
                var email = s.primaryEmail();
                Integer running = jdbc.queryForObject("""
                        SELECT count(*) FROM crm.sequence_enrollment WHERE stakeholder_id = :s AND status IN ('RUNNING','PAUSED')""",
                        Map.of("s", sid), Integer.class);
                rules.checkEnroll(email.isPresent(), s.doNotContact(), running != null && running > 0);
                String warning = null;
                if (config.flag("same_company_same_day_warning", true)) {
                    Integer same = jdbc.queryForObject("""
                            SELECT count(*) FROM crm.sequence_enrollment e JOIN crm.stakeholder st ON st.id = e.stakeholder_id
                             WHERE st.account_id = :a AND e.stakeholder_id <> :s AND e.status = 'RUNNING'
                               AND (e.next_send_at AT TIME ZONE 'Asia/Kolkata')::date = (CAST(:first AS timestamptz) AT TIME ZONE 'Asia/Kolkata')::date""",
                            new MapSqlParameterSource().addValue("a", s.accountId()).addValue("s", sid).addValue("first", Db.ts(first)),
                            Integer.class);
                    if (same != null && same > 0) warning = "Another person at " + a.name() + " is emailed the same day";
                }
                UUID id = jdbc.queryForObject("""
                        INSERT INTO crm.sequence_enrollment (sequence_id, stakeholder_id, email_id, enrolled_by, status, current_step_no,
                                                             started_from_call, next_send_at, started_at)
                        VALUES (:seq, :s, :e, :by, 'RUNNING', 1, :call, :next, :started) RETURNING id""",
                        new MapSqlParameterSource().addValue("seq", seq).addValue("s", sid).addValue("e", email.get().id())
                                .addValue("by", actor.userId()).addValue("call", startedFromCall).addValue("next", Db.ts(first))
                                .addValue("started", Db.ts(Instant.now(clock))), UUID.class);
                out.add(new StartResult(sid, "ENROLLED", id, warning));
            } catch (DomainException e) {
                out.add(new StartResult(sid, "SKIPPED", null, e.getMessage()));
            }
        }
        return out;
    }

    @Transactional
    public void stop(UUID enrollmentId, StopReason reason) {
        jdbc.update("""
                UPDATE crm.sequence_enrollment SET status = CASE WHEN :r = 'COMPLETED' THEN 'COMPLETED' ELSE 'STOPPED' END,
                  stop_reason = :r, ended_at = now(), next_send_at = NULL WHERE id = :id AND status IN ('RUNNING','PAUSED')""",
                Map.of("r", reason.name(), "id", enrollmentId));
    }

    @Transactional
    public void pause(UUID enrollmentId, boolean pause) {
        jdbc.update("""
                UPDATE crm.sequence_enrollment SET status = :s, next_send_at = CASE WHEN :s = 'RUNNING' THEN now() ELSE next_send_at END
                 WHERE id = :id AND status IN ('RUNNING','PAUSED')""", Map.of("s", pause ? "PAUSED" : "RUNNING", "id", enrollmentId));
    }

    /** M3-6: stop sequences at an account once its L1 is booked. */
    @EventListener
    public void onMeetingBooked(MeetingBooked e) {
        if (e.level() != MeetingLevel.L1) return;
        jdbc.update("""
                UPDATE crm.sequence_enrollment SET status = 'STOPPED', stop_reason = 'L1_BOOKED', ended_at = now(), next_send_at = NULL
                 WHERE status IN ('RUNNING','PAUSED')
                   AND stakeholder_id IN (SELECT id FROM crm.stakeholder WHERE account_id = :a)""", Map.of("a", e.accountId()));
    }

    /**
     * A prospect handed to another BDR (manual reassignment or a leaver's handover): sequences the old BDR started at
     * that account move to the new BDR, paused, so nothing keeps going out from the old person's Gmail. The new BDR
     * resumes them from the Sequences page, sending from their own mailbox.
     */
    @EventListener
    public void onAccountAssigned(com.prosperr.crm.assignment.api.AccountAssigned e) {
        if (!"BDR".equals(e.ownerType()) || e.previousOwnerId() == null || e.newOwnerId() == null) return;
        jdbc.update("""
                UPDATE crm.sequence_enrollment SET enrolled_by = :to,
                       status = CASE WHEN status = 'RUNNING' THEN 'PAUSED' ELSE status END
                 WHERE enrolled_by = :from AND status IN ('RUNNING','PAUSED')
                   AND stakeholder_id IN (SELECT id FROM crm.stakeholder WHERE account_id = :a)""",
                Map.of("to", e.newOwnerId(), "from", e.previousOwnerId(), "a", e.accountId()));
    }

    /** Public unsubscribe link (DPDP / PRD privacy): do-not-contact + stop with OPT_OUT. */
    @Transactional
    public boolean unsubscribe(UUID enrollmentId) {
        List<UUID> s = jdbc.queryForList("SELECT stakeholder_id FROM crm.sequence_enrollment WHERE id = :id", Map.of("id", enrollmentId), UUID.class);
        if (s.isEmpty()) return false;
        stakeholders.setDoNotContact(s.get(0), "Unsubscribed from email");
        stop(enrollmentId, StopReason.OPT_OUT);
        return true;
    }

    // ------------------------------------------------------------------ engine (M3-3, M3-6, M3-8, M3-9, M3-10)

    /** Called every minute by SequenceJobs. Each enrollment is processed in its own transaction. */
    public int processDue() {
        List<UUID> due = jdbc.queryForList("""
                SELECT id FROM crm.sequence_enrollment WHERE status = 'RUNNING' AND next_send_at <= :now ORDER BY next_send_at LIMIT 200""",
                Map.of("now", Db.ts(Instant.now(clock))), UUID.class);
        int sent = 0;
        for (UUID id : due) {
            Boolean ok = tx.execute(st -> processOne(id));
            if (Boolean.TRUE.equals(ok)) sent++;
        }
        return sent;
    }

    record Enrollment(UUID id, UUID sequenceId, UUID stakeholderId, UUID emailId, UUID ownerId, int step, boolean fromCall,
                      String threadId, Instant startedAt) {}

    record Step(int stepNo, UUID templateId, int dayOffset, boolean sameThread, String recipient, String taskType) {}

    boolean processOne(UUID enrollmentId) {
        Enrollment e = jdbc.query("""
                SELECT id, sequence_id, stakeholder_id, email_id, enrolled_by, current_step_no, started_from_call, gmail_thread_id, started_at
                  FROM crm.sequence_enrollment WHERE id = :id AND status = 'RUNNING' FOR UPDATE SKIP LOCKED""", Map.of("id", enrollmentId),
                (rs, i) -> new Enrollment(Db.uuid(rs, "id"), Db.uuid(rs, "sequence_id"), Db.uuid(rs, "stakeholder_id"),
                        Db.uuid(rs, "email_id"), Db.uuid(rs, "enrolled_by"), rs.getInt("current_step_no"),
                        rs.getBoolean("started_from_call"), rs.getString("gmail_thread_id"), Db.instant(rs, "started_at")))
                .stream().findFirst().orElse(null);
        if (e == null) return false;
        Instant now = Instant.now(clock);
        StakeholderView s = stakeholders.get(e.stakeholderId());
        AccountView a = accounts.get(s.accountId());

        // M3-6: check the thread before every send
        MailGateway.ThreadState ts = e.threadId() == null ? new MailGateway.ThreadState(false, false, false) : mail.check(e.ownerId(), e.threadId());
        boolean l1Booked = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM crm.meeting WHERE account_id = :a AND level = 'L1' AND booked_on >= :since)""",
                new MapSqlParameterSource().addValue("a", s.accountId()).addValue("since", Db.ts(e.startedAt())), Boolean.class));
        var stop = rules.stopReason(new SequenceRules.ThreadState(ts.replied(), ts.bounced(), ts.optedOut(), l1Booked, s.doNotContact()));
        if (stop.isPresent()) {
            handleStop(e, s, a, stop.get(), now);
            return false;
        }
        SendWindow w = window();
        if (!w.isOpen(now)) { reschedule(e.id(), w.nextOpen(now)); return false; }
        Mailbox box = mailbox(e.ownerId()).orElse(null);
        if (box == null || !box.status().equals("CONNECTED")) { pause(e.id(), true); return false; }
        if (!users.get(e.ownerId()).active()) { pause(e.id(), true); return false; }   // deactivated: never send from a leaver's Gmail
        Integer sentToday = jdbc.queryForObject("""
                SELECT count(*) FROM crm.email_message WHERE sender_user_id = :u AND status = 'SENT'
                   AND sent_at >= :dayStart""", new MapSqlParameterSource().addValue("u", e.ownerId())
                        .addValue("dayStart", Db.ts(LocalDate.now(clock.withZone(Ist.ZONE)).atStartOfDay(Ist.ZONE).toInstant())), Integer.class);
        if (!rules.underDailyCap(sentToday == null ? 0 : sentToday, box.cap())) {
            reschedule(e.id(), w.nextOpen(LocalDate.now(clock.withZone(Ist.ZONE)).plusDays(1).atStartOfDay(Ist.ZONE).toInstant()));
            return false;
        }
        OffsetDateTime lastSentAt = jdbc.queryForObject("SELECT max(sent_at) FROM crm.email_message WHERE sender_user_id = :u",
                Map.of("u", e.ownerId()), OffsetDateTime.class);
        Instant lastSent = lastSentAt == null ? null : lastSentAt.toInstant();
        if (lastSent != null && lastSent.plusSeconds(box.gapSeconds()).isAfter(now)) {
            reschedule(e.id(), lastSent.plusSeconds(box.gapSeconds()));
            return false;
        }

        List<Step> steps = jdbc.query("""
                SELECT step_no, template_id, day_offset, in_same_thread, recipient, creates_task_type FROM crm.sequence_step
                 WHERE sequence_id = :s ORDER BY step_no""", Map.of("s", e.sequenceId()),
                (rs, i) -> new Step(rs.getInt(1), Db.uuid(rs, "template_id"), rs.getInt(3), rs.getBoolean(4), rs.getString(5), rs.getString(6)));
        Step step = steps.stream().filter(x -> x.stepNo() == e.step()).findFirst().orElse(null);
        if (step == null) { stop(e.id(), StopReason.COMPLETED); return false; }

        UserView owner = users.get(e.ownerId());
        UUID callTask = null;
        String callDue = null;
        if (step.taskType() != null) {
            callTask = tasks.create(new TaskApi.NewTask(s.accountId(), s.id(), null, null, TaskType.valueOf(step.taskType()),
                    "Call " + s.fullName() + " at " + a.name() + ": sequence ended with no reply", now, e.ownerId(),
                    TaskSource.SEQUENCE, null, null));
            callDue = now.atZone(Ist.ZONE).format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        }
        MergeContext ctx = new MergeContext(s.firstName(), a.name(), s.designation(), owner.firstName(), owner.fullName(),
                owner.phone(), config.text("product_link", null),
                props.app().frontendUrl() + "/stakeholders/" + s.id(), callDue, s.department(), e.fromCall());
        boolean toBdr = "BDR".equals(step.recipient());
        String to = toBdr ? box.address() : emailAddress(e.emailId());
        UUID msgId = UUID.randomUUID();
        try {
            TemplateRenderer.Rendered r = templates.render(step.templateId(), ctx);
            String body = toBdr ? r.body() : r.body() + "\n\n--\nTo stop these emails: " + props.app().baseUrl() + "/public/unsubscribe/" + e.id();
            MailGateway.SentMail sent = mail.send(new MailGateway.OutgoingMail(e.ownerId(), box.address(), to, r.subject(), body,
                    step.sameThread() && !toBdr ? e.threadId() : null));
            jdbc.update("""
                    INSERT INTO crm.email_message (id, enrollment_id, sequence_step_id, stakeholder_id, sender_user_id, direction, to_address,
                                                   subject, body_rendered, status, gmail_message_id, gmail_thread_id, scheduled_for, sent_at)
                    SELECT :id, :e, ss.id, :s, :u, 'OUTBOUND', :to, :subj, :body, 'SENT', :gm, :gt, :sched, :now
                      FROM crm.sequence_step ss WHERE ss.sequence_id = :seq AND ss.step_no = :step""",
                    new MapSqlParameterSource().addValue("id", msgId).addValue("e", e.id()).addValue("s", s.id())
                            .addValue("u", e.ownerId()).addValue("to", to).addValue("subj", r.subject()).addValue("body", body)
                            .addValue("gm", sent.messageId()).addValue("gt", sent.threadId()).addValue("sched", Db.ts(now))
                            .addValue("now", Db.ts(now)).addValue("seq", e.sequenceId()).addValue("step", step.stepNo()));
            if (!toBdr) {
                activities.log(new ActivityApi.NewActivity(s.accountId(), s.id(), null, null, msgId, null, ActivityType.EMAIL, null,
                        "Sequence step " + step.stepNo() + ": " + r.subject(), now, e.ownerId()));
                if (e.threadId() == null) {
                    jdbc.update("UPDATE crm.sequence_enrollment SET gmail_thread_id = :t WHERE id = :id", Map.of("t", sent.threadId(), "id", e.id()));
                }
                if (s.engagement() == EngagementStatus.NOT_CONTACTED) stakeholders.setEngagement(s.id(), EngagementStatus.CONTACTED, null);
            }
            Optional<Step> next = steps.stream().filter(x -> x.stepNo() > step.stepNo()).findFirst();
            if (next.isPresent()) {
                jdbc.update("""
                        UPDATE crm.sequence_enrollment SET current_step_no = :n, last_sent_at = :now, next_send_at = :next WHERE id = :id""",
                        new MapSqlParameterSource().addValue("n", next.get().stepNo()).addValue("now", Db.ts(now))
                                .addValue("next", Db.ts(w.stepTime(e.startedAt(), next.get().dayOffset()))).addValue("id", e.id()));
            } else {
                jdbc.update("UPDATE crm.sequence_enrollment SET last_sent_at = :now WHERE id = :id", Map.of("now", Db.ts(now), "id", e.id()));
                stop(e.id(), StopReason.COMPLETED);
            }
            return true;
        } catch (RuntimeException ex) {
            jdbc.update("""
                    INSERT INTO crm.email_message (id, enrollment_id, stakeholder_id, sender_user_id, direction, to_address, status,
                                                   scheduled_for, error)
                    VALUES (:id, :e, :s, :u, 'OUTBOUND', :to, 'FAILED', :now, :err)""",
                    new MapSqlParameterSource().addValue("id", msgId).addValue("e", e.id()).addValue("s", s.id())
                            .addValue("u", e.ownerId()).addValue("to", to).addValue("now", Db.ts(now)).addValue("err", ex.getMessage()));
            if (callTask != null) jdbc.update("DELETE FROM crm.task WHERE id = :t", Map.of("t", callTask));
            Integer fails = jdbc.queryForObject("SELECT count(*) FROM crm.email_message WHERE enrollment_id = :e AND status = 'FAILED'",
                    Map.of("e", e.id()), Integer.class);
            if (fails != null && fails >= config.number("sequence_max_send_failures", 3)) pause(e.id(), true);
            else reschedule(e.id(), now.plusSeconds(1800));
            events.publishEvent(new OutreachEvents.SequenceSendFailed(e.id(), e.ownerId(), ex.getMessage()));
            return false;
        }
    }

    void handleStop(Enrollment e, StakeholderView s, AccountView a, StopReason reason, Instant now) {
        stop(e.id(), reason);
        if (reason == StopReason.REPLY) {
            jdbc.update("UPDATE crm.sequence_enrollment SET reply_received_at = :now WHERE id = :id", Map.of("now", Db.ts(now), "id", e.id()));
            stakeholders.setEngagement(s.id(), EngagementStatus.CONNECTED, null);
            tasks.create(new TaskApi.NewTask(s.accountId(), s.id(), null, null, TaskType.CALL,
                    s.fullName() + " replied — call them", now, e.ownerId(), TaskSource.SEQUENCE, null, null));
            activities.log(new ActivityApi.NewActivity(s.accountId(), s.id(), null, null, null, null, ActivityType.EMAIL, null,
                    "Reply received", now, e.ownerId()));
            events.publishEvent(new OutreachEvents.EmailReplyReceived(s.accountId(), s.id(), e.ownerId()));
        } else if (reason == StopReason.BOUNCE) {
            stakeholders.markEmailBounced(e.emailId(), now);
        } else if (reason == StopReason.OPT_OUT) {
            stakeholders.setDoNotContact(s.id(), "Opted out by email");
        }
    }

    /** Poll running threads for replies between sends, so a reply sets Connected promptly (M3-6). */
    public int pollReplies() {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM crm.sequence_enrollment WHERE status = 'RUNNING' AND gmail_thread_id IS NOT NULL LIMIT 500""",
                Map.of(), UUID.class);
        int stopped = 0;
        for (UUID id : ids) {
            Boolean s = tx.execute(st -> {
                Map<String, Object> r = jdbc.queryForMap("""
                        SELECT id, sequence_id, stakeholder_id, email_id, enrolled_by, current_step_no, started_from_call,
                               gmail_thread_id, started_at FROM crm.sequence_enrollment WHERE id = :id""", Map.of("id", id));
                Enrollment e = new Enrollment(id, (UUID) r.get("sequence_id"), (UUID) r.get("stakeholder_id"), (UUID) r.get("email_id"),
                        (UUID) r.get("enrolled_by"), ((Number) r.get("current_step_no")).intValue(), (Boolean) r.get("started_from_call"),
                        (String) r.get("gmail_thread_id"), ((java.sql.Timestamp) r.get("started_at")).toInstant());
                MailGateway.ThreadState ts = mail.check(e.ownerId(), e.threadId());
                var stop = rules.stopReason(new SequenceRules.ThreadState(ts.replied(), ts.bounced(), ts.optedOut(), false, false));
                if (stop.isEmpty()) return false;
                StakeholderView sv = stakeholders.get(e.stakeholderId());
                handleStop(e, sv, accounts.get(sv.accountId()), stop.get(), Instant.now(clock));
                return true;
            });
            if (Boolean.TRUE.equals(s)) stopped++;
        }
        return stopped;
    }

    void reschedule(UUID id, Instant at) {
        jdbc.update("UPDATE crm.sequence_enrollment SET next_send_at = :t WHERE id = :id", Map.of("t", Db.ts(at), "id", id));
    }

    String emailAddress(UUID emailId) {
        return jdbc.queryForList("SELECT email FROM crm.stakeholder_email WHERE id = :id AND is_valid", Map.of("id", emailId), String.class)
                .stream().findFirst().orElseThrow(() -> new DomainException("NO_VALID_EMAIL", "Email is no longer valid"));
    }

    // ------------------------------------------------------------------ preview (what the person will receive, and when)

    /** Every step of the sequence rendered for this stakeholder, with the planned send time; nothing is sent. */
    public Map<String, Object> preview(UUID stakeholderId, UUID sequenceId, Actor actor) {
        StakeholderView s = stakeholders.get(stakeholderId);
        AccountView a = accounts.get(s.accountId());
        guard.require(actor, a.zone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.LOG_ACTIVITY, s.accountId());
        UUID seq = sequenceId != null ? sequenceId : jdbc.queryForObject(
                "SELECT id FROM crm.email_sequence WHERE is_default AND is_active", Map.of(), UUID.class);
        UserView owner = users.get(actor.userId());
        MergeContext ctx = new MergeContext(s.firstName(), a.name(), s.designation(), owner.firstName(), owner.fullName(),
                owner.phone(), config.text("product_link", null), props.app().frontendUrl() + "/stakeholders/" + s.id(),
                "the day it is sent", s.department(), false);
        SendWindow w = window();
        Instant start = w.nextOpen(Instant.now(clock));
        String toEmail = s.primaryEmail().flatMap(e -> jdbc.queryForList("SELECT email FROM crm.stakeholder_email WHERE id = :id AND is_valid",
                Map.of("id", e.id()), String.class).stream().findFirst()).orElse(null);
        List<Map<String, Object>> steps = new ArrayList<>();
        jdbc.query("SELECT step_no, template_id, day_offset, in_same_thread, recipient FROM crm.sequence_step WHERE sequence_id = :s ORDER BY step_no",
                Map.of("s", seq), rs -> {
                    TemplateRenderer.Rendered r = templates.render(Db.uuid(rs, "template_id"), ctx);
                    boolean toBdr = "BDR".equals(rs.getString("recipient"));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("step", rs.getInt("step_no"));
                    m.put("dayOffset", rs.getInt("day_offset"));
                    m.put("sendAt", w.stepTime(start, rs.getInt("day_offset")));
                    m.put("sameThread", rs.getBoolean("in_same_thread"));
                    m.put("toBdr", toBdr);
                    m.put("to", toBdr ? owner.fullName() + " (reminder to you)" : toEmail);
                    m.put("subject", r.subject());
                    m.put("body", r.body());
                    steps.add(m);
                });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sequenceId", seq);
        out.put("stakeholder", s.fullName());
        out.put("account", a.name());
        out.put("email", toEmail);
        out.put("doNotContact", s.doNotContact());
        out.put("mailbox", mailbox(actor.userId()).map(Mailbox::address).orElse(null));
        out.put("mailboxConnected", mailbox(actor.userId()).map(m -> m.status().equals("CONNECTED")).orElse(false));
        out.put("running", !jdbc.queryForList("SELECT 1 FROM crm.sequence_enrollment WHERE stakeholder_id = :s AND status IN ('RUNNING','PAUSED')",
                Map.of("s", stakeholderId)).isEmpty());
        out.put("steps", steps);
        return out;
    }

    // ------------------------------------------------------------------ monitor (M3-10) and performance (M3-12)

    public Map<String, Object> monitor(UUID ownerId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("u", ownerId).addValue("now", Db.ts(Instant.now(clock)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", jdbc.queryForList("""
                SELECT status, coalesce(stop_reason, '') AS "stopReason", count(*) AS n FROM crm.sequence_enrollment
                 WHERE CAST(:u AS uuid) IS NULL OR enrolled_by = :u GROUP BY 1, 2 ORDER BY 1, 2""", p));
        out.put("overdue", jdbc.queryForList("""
                SELECT e.id, st.first_name || coalesce(' ' || st.last_name, '') AS "stakeholder", a.name AS "account",
                       e.current_step_no AS "step", e.next_send_at AS "nextSendAt"
                  FROM crm.sequence_enrollment e JOIN crm.stakeholder st ON st.id = e.stakeholder_id JOIN crm.account a ON a.id = st.account_id
                 WHERE e.status = 'RUNNING' AND e.next_send_at < CAST(:now AS timestamptz) - interval '1 hour'
                   AND (CAST(:u AS uuid) IS NULL OR e.enrolled_by = :u)""", p));
        out.put("failedLast7Days", jdbc.queryForList("""
                SELECT m.enrollment_id AS "enrollmentId", m.to_address AS "to", m.error, m.scheduled_for AS "at"
                  FROM crm.email_message m WHERE m.status = 'FAILED' AND m.created_at > CAST(:now AS timestamptz) - interval '7 days'
                   AND (CAST(:u AS uuid) IS NULL OR m.sender_user_id = :u) ORDER BY m.created_at DESC""", p));
        out.put("enrollments", jdbc.queryForList("""
                SELECT e.id, st.first_name || coalesce(' ' || st.last_name, '') AS "stakeholder", a.name AS "account", e.status,
                       st.id AS "stakeholderId", a.id AS "accountId", st.designation,
                       (SELECT count(*) FROM crm.email_message m WHERE m.enrollment_id = e.id AND m.status = 'SENT') AS "sent",
                       (SELECT count(*) FROM crm.sequence_step ss WHERE ss.sequence_id = e.sequence_id AND ss.recipient = 'STAKEHOLDER') AS "steps",
                       e.started_at AS "startedAt",
                       e.current_step_no AS "step", e.last_sent_at AS "lastSentAt", e.next_send_at AS "nextSendAt",
                       e.stop_reason AS "stopReason", e.reply_received_at AS "replyAt"
                  FROM crm.sequence_enrollment e JOIN crm.stakeholder st ON st.id = e.stakeholder_id JOIN crm.account a ON a.id = st.account_id
                 WHERE CAST(:u AS uuid) IS NULL OR e.enrolled_by = :u ORDER BY e.started_at DESC LIMIT 500""", p));
        return out;
    }

    public List<Map<String, Object>> performance() {
        return jdbc.queryForList("""
                SELECT t.code AS "template", ss.step_no AS "step", count(m.id) AS "sent",
                       count(*) FILTER (WHERE e.stop_reason = 'REPLY' AND e.current_step_no = ss.step_no) AS "repliesAfterStep",
                       round(100.0 * count(*) FILTER (WHERE e.stop_reason = 'REPLY' AND e.current_step_no = ss.step_no)
                             / nullif(count(m.id), 0), 1) AS "replyRatePct"
                  FROM crm.sequence_step ss JOIN crm.email_template t ON t.id = ss.template_id
                  LEFT JOIN crm.email_message m ON m.sequence_step_id = ss.id AND m.status = 'SENT'
                  LEFT JOIN crm.sequence_enrollment e ON e.id = m.enrollment_id
                 GROUP BY t.code, ss.step_no ORDER BY ss.step_no""", Map.of());
    }

    public Map<String, Object> stakeholderSequence(UUID stakeholderId) {
        return jdbc.queryForList("""
                SELECT e.id, e.status, e.current_step_no AS "step", e.last_sent_at AS "lastSentAt", e.next_send_at AS "nextSendAt",
                       e.stop_reason AS "stopReason" FROM crm.sequence_enrollment e WHERE e.stakeholder_id = :s
                 ORDER BY e.started_at DESC LIMIT 1""", Map.of("s", stakeholderId)).stream().findFirst().orElse(Map.of());
    }

    public void requireExists(UUID enrollmentId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM crm.sequence_enrollment WHERE id = :id", Map.of("id", enrollmentId), Integer.class);
        if (n == null || n == 0) throw new NotFoundException("Enrollment", enrollmentId);
    }
}
