package com.prosperr.crm.account.application;

import com.prosperr.crm.account.api.StakeholderAdded;
import com.prosperr.crm.account.api.StakeholderApi;
import com.prosperr.crm.account.api.StakeholderEngagementChanged;
import com.prosperr.crm.account.api.StakeholderView;
import com.prosperr.crm.account.domain.DesignationResolver;
import com.prosperr.crm.account.domain.Normalizer;
import com.prosperr.crm.account.infrastructure.*;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.config.api.DesignationMapped;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.NotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class StakeholderService implements StakeholderApi {
    private final StakeholderRepository stakeholders;
    private final StakeholderEmailRepository emails;
    private final StakeholderPhoneRepository phones;
    private final AccountService accounts;
    private final ReferenceData refs;
    private final AuditApi audit;
    private final ApplicationEventPublisher events;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public StakeholderService(StakeholderRepository stakeholders, StakeholderEmailRepository emails,
                              StakeholderPhoneRepository phones, AccountService accounts, ReferenceData refs,
                              AuditApi audit, ApplicationEventPublisher events, NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.stakeholders = stakeholders; this.emails = emails; this.phones = phones; this.accounts = accounts;
        this.refs = refs; this.audit = audit; this.events = events; this.jdbc = jdbc; this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    @Override
    @Transactional
    public StakeholderView create(CreateStakeholder c, Actor actor) {
        var account = accounts.get(c.accountId());
        Action needed = account.zone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.ADD_STAKEHOLDER;
        if (account.status() == AccountStatus.INACTIVE && actor.role() == Role.BDR) needed = Action.REACTIVATE; // M8-6 revival
        accounts.require(actor, needed, c.accountId());

        String first, last;
        if (c.firstName() != null && !c.firstName().isBlank()) {
            first = c.firstName().trim();
            last = Normalizer.clean(c.lastName()).orElse(null);
        } else {
            String[] n = Normalizer.splitName(c.fullName());
            first = n[0]; last = n[1];
        }
        List<String> cleanEmails = c.emails() == null ? List.of() : c.emails().stream()
                .map(Normalizer::email).flatMap(Optional::stream).distinct().toList();
        List<String[]> cleanPhones = c.phones() == null ? List.of() : c.phones().stream()
                .map(p -> new String[]{p, Normalizer.phoneE164(p).orElse(null)}).filter(p -> p[1] != null).toList();
        if (c.emails() != null) for (String e : c.emails()) {
            if (e != null && !e.isBlank() && Normalizer.email(e).isEmpty()) throw new DomainException("INVALID_EMAIL", "'" + e.trim() + "' is not a valid e-mail address");
        }
        if (c.phones() != null) for (String p : c.phones()) {
            if (p != null && !p.isBlank() && Normalizer.phoneE164(p).isEmpty()) throw new DomainException("INVALID_PHONE", "'" + p.trim() + "' is not a valid phone number");
        }
        String linkedIn = Normalizer.linkedIn(c.linkedinUrl()).orElse(null);
        checkDuplicates(cleanEmails, cleanPhones, linkedIn, c.allowSharedPhone());

        Stakeholder s = new Stakeholder();
        s.setAccountId(c.accountId());
        UUID owner = c.bdrOwnerId() != null ? c.bdrOwnerId() : (actor.role() == Role.BDR ? actor.userId() : null);
        s.setBdrOwnerId(owner);
        s.setFirstName(first);
        s.setLastName(last);
        s.setBuyingRole(c.buyingRole());
        s.setLinkedinUrl(linkedIn);
        s.setReferredById(c.referredById());
        s.setDecisionMaker(c.decisionMaker());
        s.setCreatedBy(actor.userId());
        applyDesignation(s, c.designation(), account.sizeBucket());
        if (c.department() != null) s.setDepartment(c.department());
        s.setConnectionType(c.connectionType() != null ? c.connectionType() : connectionType(s, c.referredById()));
        if (c.primarySpoc()) clearPrimarySpoc(c.accountId());
        s.setPrimarySpoc(c.primarySpoc());
        stakeholders.saveAndFlush(s);

        boolean first1 = true;
        for (String e : cleanEmails) {
            StakeholderEmail se = new StakeholderEmail();
            se.setStakeholderId(s.getId()); se.setEmail(e); se.setPrimary(first1); first1 = false;
            emails.save(se);
        }
        boolean firstP = true;
        for (String[] p : cleanPhones) {
            StakeholderPhone sp = new StakeholderPhone();
            sp.setStakeholderId(s.getId()); sp.setPhoneRaw(p[0]); sp.setPhoneE164(p[1]); sp.setPrimary(firstP); firstP = false;
            phones.save(sp);
        }
        if (owner != null) accounts.addBdr(c.accountId(), owner);
        audit.record("stakeholder", s.getId(), AuditApi.Action.CREATE, null, null, s.getFirstName(), actor.userId());
        events.publishEvent(new StakeholderAdded(c.accountId(), s.getId(), c.referredById(), actor.userId()));
        return view(s, actor);
    }

    private void checkDuplicates(List<String> cleanEmails, List<String[]> cleanPhones, String linkedIn, boolean allowSharedPhone) {
        for (String e : cleanEmails) {
            emails.findByEmailNormalized(e).ifPresent(x -> { throw duplicate("email " + e, x.getStakeholderId()); });
        }
        if (linkedIn != null) {
            stakeholders.findByLinkedinUrl(linkedIn).ifPresent(x -> { throw duplicate("LinkedIn profile", x.getId()); });
        }
        if (!allowSharedPhone) {
            for (String[] p : cleanPhones) {
                List<StakeholderPhone> hits = phones.findByPhoneE164(p[1]);
                if (!hits.isEmpty()) throw duplicate("phone " + p[1], hits.get(0).getStakeholderId());
            }
        }
    }

    /** "This person already exists, owned by X" — never a silent duplicate (PRD duplicate rules, M2-9). */
    private DomainException duplicate(String what, UUID stakeholderId) {
        String owner = jdbc.query("""
                SELECT s.first_name || coalesce(' ' || s.last_name, '') || ' at ' || a.name
                       || coalesce(' (owned by ' || u.full_name || ')', '')
                  FROM crm.stakeholder s JOIN crm.account a ON a.id = s.account_id
                  LEFT JOIN crm.app_user u ON u.id = s.bdr_owner_id WHERE s.id = :id""",
                Map.of("id", stakeholderId), rs -> rs.next() ? rs.getString(1) : "another stakeholder");
        return new DomainException("STAKEHOLDER_EXISTS", "A stakeholder with this " + what + " already exists: " + owner);
    }

    private void applyDesignation(Stakeholder s, String designation, SizeBucket bucket) {
        s.setDesignation(Normalizer.clean(designation).orElse(null));
        DesignationResolver.Resolution r = refs.designations().resolve(designation, bucket);
        if (r.mapped()) {
            s.setDesignationMapId(refs.designations().find(designation)
                    .flatMap(e -> mapId(e.designationNormalized())).orElse(null));
            s.setLevel(r.level().shortValue());
            s.setDepartment(r.department());
            s.setRightPoc(r.rightPoc());
        } else {
            s.setDesignationMapId(null);
            s.setLevel(null);
            s.setRightPoc(false);
        }
    }

    private Optional<UUID> mapId(String normalized) {
        return jdbc.queryForList("SELECT id FROM crm.designation_map WHERE designation_normalized = :n",
                Map.of("n", normalized), UUID.class).stream().findFirst();
    }

    /** BRD 4.2: direct senior / junior via senior referral / direct junior. */
    private ConnectionType connectionType(Stakeholder s, UUID referredById) {
        if (s.getLevel() == null) return null;
        if (s.getLevel() <= 2) return ConnectionType.DIRECT_SENIOR;
        if (referredById != null) {
            Short refLevel = stakeholders.findById(referredById).map(Stakeholder::getLevel).orElse(null);
            if (refLevel != null && refLevel <= 2) return ConnectionType.JUNIOR_VIA_SENIOR_REFERRAL;
        }
        return ConnectionType.DIRECT_JUNIOR;
    }

    private void clearPrimarySpoc(UUID accountId) {
        jdbc.update("UPDATE crm.stakeholder SET is_primary_spoc = false WHERE account_id = :a AND is_primary_spoc",
                Map.of("a", accountId));
    }

    // ------------------------------------------------------------------ update (controller)

    public record StakeholderUpdate(String firstName, String lastName, String designation, Department department,
                                    BuyingRole buyingRole, Boolean primarySpoc, Boolean decisionMaker, String linkedinUrl,
                                    Instant nextFollowUpAt, Integer version) {}

    @Transactional
    public StakeholderView update(UUID id, StakeholderUpdate u, Actor actor) {
        Stakeholder s = entity(id);
        var account = accounts.get(s.getAccountId());
        requireEdit(actor, s, account.zone());
        if (u.version() != null && u.version() != s.getVersion()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Stakeholder.class, id);
        }
        if (u.firstName() != null) s.setFirstName(u.firstName().trim());
        if (u.lastName() != null) s.setLastName(u.lastName().trim());
        if (u.designation() != null) {
            Short before = s.getLevel();
            applyDesignation(s, u.designation(), account.sizeBucket());
            audit.change("stakeholder", id, "level", before, s.getLevel(), actor.userId());
        }
        if (u.department() != null) s.setDepartment(u.department());
        if (u.buyingRole() != null) s.setBuyingRole(u.buyingRole());
        if (u.decisionMaker() != null) s.setDecisionMaker(u.decisionMaker());
        if (Boolean.TRUE.equals(u.primarySpoc()) && !s.isPrimarySpoc()) {
            clearPrimarySpoc(s.getAccountId());
            s.setPrimarySpoc(true);
        } else if (Boolean.FALSE.equals(u.primarySpoc())) {
            s.setPrimarySpoc(false);
        }
        if (u.linkedinUrl() != null) s.setLinkedinUrl(Normalizer.linkedIn(u.linkedinUrl()).orElse(null));
        if (u.nextFollowUpAt() != null) s.setNextFollowUpAt(u.nextFollowUpAt());
        stakeholders.saveAndFlush(s);
        return view(s, actor);
    }

    private void requireEdit(Actor actor, Stakeholder s, Zone zone) {
        if (zone == Zone.PROSPECTING) {
            accounts.require(actor, Action.WORK_PROSPECT, s.getAccountId());
            if (actor.role() == Role.BDR && s.getBdrOwnerId() != null && !s.getBdrOwnerId().equals(actor.userId())) {
                throw new com.prosperr.crm.shared.web.ForbiddenException("This stakeholder belongs to another BDR");
            }
        } else {
            accounts.require(actor, Action.UPDATE_ENGAGEMENT, s.getAccountId());
        }
    }

    @Transactional
    public StakeholderView addEmail(UUID id, String email, boolean primary, Actor actor) {
        Stakeholder s = entity(id);
        requireEdit(actor, s, accounts.get(s.getAccountId()).zone());
        String e = Normalizer.email(email).orElseThrow(() -> new DomainException("EMAIL_INVALID", "Invalid email"));
        checkDuplicates(List.of(e), List.of(), null, true);
        if (primary) jdbc.update("UPDATE crm.stakeholder_email SET is_primary = false WHERE stakeholder_id = :s", Map.of("s", id));
        StakeholderEmail se = new StakeholderEmail();
        se.setStakeholderId(id); se.setEmail(e); se.setPrimary(primary);
        emails.saveAndFlush(se);
        return view(s, actor);
    }

    @Transactional
    public StakeholderView addPhone(UUID id, String phone, boolean primary, boolean whatsapp, boolean allowShared, Actor actor) {
        Stakeholder s = entity(id);
        requireEdit(actor, s, accounts.get(s.getAccountId()).zone());
        String e164 = Normalizer.phoneE164(phone).orElseThrow(() -> new DomainException("PHONE_INVALID", "Invalid phone number"));
        checkDuplicates(List.of(), List.<String[]>of(new String[]{phone, e164}), null, allowShared);
        if (primary) jdbc.update("UPDATE crm.stakeholder_phone SET is_primary = false WHERE stakeholder_id = :s", Map.of("s", id));
        StakeholderPhone sp = new StakeholderPhone();
        sp.setStakeholderId(id); sp.setPhoneRaw(phone); sp.setPhoneE164(e164); sp.setPrimary(primary); sp.setWhatsapp(whatsapp);
        phones.saveAndFlush(sp);
        return view(s, actor);
    }

    /** DPDP Act: erase personal data on request; the stakeholder row stays so history and reports remain consistent. */
    @Transactional
    public void erase(UUID id, Actor actor) {
        Stakeholder s = entity(id);
        jdbc.update("DELETE FROM crm.stakeholder_email WHERE stakeholder_id = :s", Map.of("s", id));
        jdbc.update("DELETE FROM crm.stakeholder_phone WHERE stakeholder_id = :s", Map.of("s", id));
        s.setFirstName("Erased");
        s.setLastName(null);
        s.setLinkedinUrl(null);
        s.setDoNotContact(true);
        s.setDncReason("Deleted on request (DPDP)");
        audit.record("stakeholder", id, AuditApi.Action.UPDATE, "erased", null, "DPDP erase", actor.userId());
    }

    // ------------------------------------------------------------------ commands for other modules

    @Override
    @Transactional
    public void setEngagement(UUID stakeholderId, EngagementStatus status, Actor actor) {
        Stakeholder s = entity(stakeholderId);
        EngagementStatus before = s.getEngagementStatus();
        if (before == status) return;
        s.setEngagementStatus(status);
        if (status == EngagementStatus.CONNECTED || status == EngagementStatus.MEETING_SCHEDULED
                || status == EngagementStatus.MEETING_COMPLETED) {
            s.setCallAttempts((short) 0);
        }
        var account = accounts.get(s.getAccountId());
        events.publishEvent(new StakeholderEngagementChanged(s.getAccountId(), stakeholderId, before, status,
                actor == null ? null : actor.userId(), account.aeOwnerId(), s.getBdrOwnerId()));
    }

    /** Manual engagement change from the multithreading screen (M7-2). */
    @Transactional
    public StakeholderView changeEngagement(UUID stakeholderId, EngagementStatus status, Instant nextFollowUp, Actor actor) {
        Stakeholder s = entity(stakeholderId);
        requireEdit(actor, s, accounts.get(s.getAccountId()).zone());
        setEngagement(stakeholderId, status, actor);
        if (nextFollowUp != null) s.setNextFollowUpAt(nextFollowUp);
        return view(s, actor);
    }

    @Override
    @Transactional
    public void setCallAttempts(UUID stakeholderId, int attempts) { entity(stakeholderId).setCallAttempts((short) attempts); }

    @Override
    @Transactional
    public void setNextFollowUp(UUID stakeholderId, Instant at) { entity(stakeholderId).setNextFollowUpAt(at); }

    /** M1-3 from the UI: same edit rights as the stakeholder itself. */
    @Transactional
    public void markPhoneInvalid(UUID phoneId, Actor actor) {
        StakeholderPhone p = phones.findById(phoneId).orElseThrow(() -> new NotFoundException("Phone", phoneId));
        Stakeholder s = entity(p.getStakeholderId());
        requireEdit(actor, s, accounts.get(s.getAccountId()).zone());
        markPhoneInvalid(phoneId);
    }

    @Override
    @Transactional
    public void markPhoneInvalid(UUID phoneId) {
        StakeholderPhone p = phones.findById(phoneId).orElseThrow(() -> new NotFoundException("Phone", phoneId));
        p.setValid(false);
        p.setPrimary(false);
        p.setInvalidMarkedAt(Instant.now(clock));
    }

    @Override
    @Transactional
    public void markEmailBounced(UUID emailId, Instant at) {
        emails.findById(emailId).ifPresent(e -> { e.setValid(false); e.setBouncedAt(at); });
    }

    @Transactional
    public void setDoNotContact(UUID stakeholderId, String reason, Actor actor) {
        Stakeholder s = entity(stakeholderId);
        requireEdit(actor, s, accounts.get(s.getAccountId()).zone());
        setDoNotContact(stakeholderId, reason);
    }

    @Override
    @Transactional
    public void setDoNotContact(UUID stakeholderId, String reason) {
        Stakeholder s = entity(stakeholderId);
        s.setDoNotContact(true);
        s.setDncReason(reason);
    }

    @Override
    @Transactional
    public void setBdrOwner(UUID stakeholderId, UUID bdrId) {
        Stakeholder s = entity(stakeholderId);
        UUID before = s.getBdrOwnerId();
        s.setBdrOwnerId(bdrId);
        stakeholders.saveAndFlush(s); // moveBdr counts owners with plain SQL, so the change must be in the database first
        if (before != null) accounts.moveBdr(s.getAccountId(), before, bdrId); else accounts.addBdr(s.getAccountId(), bdrId);
    }

    /** Titles mapped by an admin from the review list: backfill stakeholders that had no level (M1-5). */
    @EventListener
    @Transactional
    public void onDesignationMapped(DesignationMapped e) {
        jdbc.update("""
                UPDATE crm.stakeholder s SET designation_map_id = d.id, level = d.level, department = d.department,
                       is_right_poc = d.is_right_poc AND d.right_poc_min_bucket IS NULL
                  FROM crm.designation_map d
                 WHERE d.designation_normalized = :n
                   AND lower(regexp_replace(btrim(s.designation), '\\s+', ' ', 'g')) = :n""",
                Map.of("n", e.designationNormalized()));
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public StakeholderView get(UUID id) { return view(entity(id), null); }

    @Override
    @Transactional(readOnly = true)
    public List<StakeholderView> forAccount(UUID accountId) { return views(stakeholders.findByAccountId(accountId), null); }

    /** Viewer-aware list: other BDRs' prospect stakeholders are masked (M2-9). */
    @Transactional(readOnly = true)
    public List<StakeholderView> forAccount(UUID accountId, Actor viewer) {
        accounts.require(viewer, Action.VIEW_PIPELINE, accountId);
        return views(stakeholders.findByAccountId(accountId), viewer);
    }

    Stakeholder entity(UUID id) { return stakeholders.findById(id).orElseThrow(() -> new NotFoundException("Stakeholder", id)); }

    StakeholderView view(Stakeholder s, Actor viewer) { return views(List.of(s), viewer).get(0); }

    List<StakeholderView> views(List<Stakeholder> list, Actor viewer) {
        if (list.isEmpty()) return List.of();
        List<UUID> ids = list.stream().map(Stakeholder::getId).toList();
        Map<UUID, List<StakeholderView.EmailView>> em = new HashMap<>();
        emails.findByStakeholderIdIn(ids).forEach(e -> em.computeIfAbsent(e.getStakeholderId(), k -> new ArrayList<>())
                .add(new StakeholderView.EmailView(e.getId(), e.getEmail(), e.isPrimary(), e.isValid())));
        Map<UUID, List<StakeholderView.PhoneView>> ph = new HashMap<>();
        phones.findByStakeholderIdIn(ids).forEach(p -> ph.computeIfAbsent(p.getStakeholderId(), k -> new ArrayList<>())
                .add(new StakeholderView.PhoneView(p.getId(), p.getPhoneRaw(), p.getPhoneE164(), p.isPrimary(), p.isWhatsapp(), p.isValid())));
        Zone zone = accounts.get(list.get(0).getAccountId()).zone();
        return list.stream().map(s -> {
            boolean masked = viewer != null && viewer.role() == Role.BDR && zone == Zone.PROSPECTING
                    && s.getBdrOwnerId() != null && !s.getBdrOwnerId().equals(viewer.userId());
            return new StakeholderView(s.getId(), s.getAccountId(), s.getBdrOwnerId(), s.getFirstName(),
                    masked ? null : s.getLastName(), s.getDesignation(), s.getDesignationMapId(),
                    s.getLevel() == null ? null : s.getLevel().intValue(), s.getDepartment(),
                    refs.priorityRank(s.getDesignationMapId()), s.getBuyingRole(), s.isRightPoc(), s.isPrimarySpoc(),
                    s.isDecisionMaker(), masked ? null : s.getEngagementStatus(), s.getConnectionType(),
                    s.getReferredById(), masked ? null : s.getLinkedinUrl(), s.isDoNotContact(),
                    masked ? 0 : s.getCallAttempts(), masked ? null : s.getLastInteractionAt(),
                    masked ? null : s.getNextFollowUpAt(),
                    masked ? List.of() : em.getOrDefault(s.getId(), List.of()),
                    masked ? List.of() : ph.getOrDefault(s.getId(), List.of()), masked);
        }).toList();
    }
}
