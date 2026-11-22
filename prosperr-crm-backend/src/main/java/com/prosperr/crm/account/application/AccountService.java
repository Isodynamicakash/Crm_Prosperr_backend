package com.prosperr.crm.account.application;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountGuard;
import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.account.domain.Normalizer;
import com.prosperr.crm.account.infrastructure.Account;
import com.prosperr.crm.account.infrastructure.AccountRepository;
import com.prosperr.crm.audit.api.AuditApi;
import com.prosperr.crm.config.api.ConfigApi;
import com.prosperr.crm.shared.domain.*;
import com.prosperr.crm.shared.security.AccessPolicy;
import com.prosperr.crm.shared.security.AccountAccess;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.NotFoundException;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountService implements AccountApi, AccountGuard {
    private final AccountRepository accounts;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager em;
    private final AuditApi audit;
    private final ConfigApi config;
    private final AccessPolicy policy = new AccessPolicy();

    public AccountService(AccountRepository accounts, NamedParameterJdbcTemplate jdbc, EntityManager em,
                          AuditApi audit, ConfigApi config) {
        this.accounts = accounts; this.jdbc = jdbc; this.em = em; this.audit = audit; this.config = config;
    }

    // ------------------------------------------------------------------ create / join

    @Override
    @Transactional
    public Resolution resolveOrCreate(CreateAccount c, Actor actor) {
        String name = Normalizer.clean(c.name()).orElseThrow(() -> new DomainException("NAME_REQUIRED", "Company name is required"));
        String normalized = Normalizer.companyName(name);
        Optional<String> domain = Normalizer.domain(c.website());

        Optional<Account> existing = findExisting(normalized, domain);
        if (existing.isEmpty()) {
            // ON CONFLICT DO NOTHING: two BDRs adding the same company at the same moment both end up on one record
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO crm.account (id, name, domain, website, industry_id, sub_industry, city_id, employee_count,
                      taxable_employee_count, license_potential, lead_source_id, apollo_id, created_by)
                    VALUES (:id, :name, :domain, :website, :industry, :sub, :city, :emp, :tax, :lic, :src, :apollo, :by)
                    ON CONFLICT DO NOTHING""",
                    new MapSqlParameterSource().addValue("id", id).addValue("name", name)
                            .addValue("domain", domain.orElse(null)).addValue("website", Normalizer.clean(c.website()).orElse(null))
                            .addValue("industry", c.industryId()).addValue("sub", c.subIndustry()).addValue("city", c.cityId())
                            .addValue("emp", c.employeeCount()).addValue("tax", c.taxableEmployeeCount())
                            .addValue("lic", c.licensePotential()).addValue("src", c.leadSourceId())
                            .addValue("apollo", c.apolloId()).addValue("by", actor.userId()));
            if (inserted == 1) {
                if (actor.role() == Role.BDR) addBdr(id, actor.userId());
                audit.record("account", id, AuditApi.Action.CREATE, null, null, name, actor.userId());
                return new Resolution(view(entity(id)), true, List.of());
            }
            existing = findExisting(normalized, domain);
        }
        Account a = existing.orElseThrow(() -> new IllegalStateException("account vanished after conflict"));
        if (actor.role() == Role.BDR) addBdr(a.getId(), actor.userId());
        return new Resolution(view(a), false, takenBy(a.getId(), actor.userId()));
    }

    private Optional<Account> findExisting(String normalized, Optional<String> domain) {
        if (domain.isPresent()) {
            Optional<Account> byDomain = accounts.findByDomain(domain.get());
            if (byDomain.isPresent()) return byDomain;
        }
        return accounts.findByNameNormalized(normalized);
    }

    /** Stakeholders other BDRs already work: name, designation and BDR only, never their history (M2-9). */
    List<TakenStakeholder> takenBy(UUID accountId, UUID exceptBdr) {
        return jdbc.query("""
                SELECT s.first_name || coalesce(' ' || s.last_name, '') AS name, s.designation, u.full_name AS bdr
                  FROM crm.stakeholder s JOIN crm.app_user u ON u.id = s.bdr_owner_id
                 WHERE s.account_id = :a AND s.bdr_owner_id IS DISTINCT FROM :me ORDER BY u.full_name, name""",
                new MapSqlParameterSource().addValue("a", accountId).addValue("me", exceptBdr),
                (rs, i) -> new TakenStakeholder(rs.getString("name"), rs.getString("designation"), rs.getString("bdr")));
    }

    public record AccountUpdate(String name, String website, UUID industryId, String subIndustry, UUID cityId,
                                Integer employeeCount, Integer taxableEmployeeCount, Integer licensePotential,
                                UUID leadSourceId, String nextStep, LocalDate nextStepDue, Integer version) {}

    /** Edit account details. Prospecting fields belong to the BDR before L1; after L1 the AE corrects them. */
    @Transactional
    public AccountView update(UUID id, AccountUpdate u, Actor actor) {
        Account a = entity(id);
        Action needed = a.getVisibilityZone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.CORRECT_QUALIFICATION;
        if (u.nextStep() != null && u.name() == null && u.licensePotential() == null && u.cityId() == null) {
            needed = a.getVisibilityZone() == Zone.PROSPECTING ? Action.WORK_PROSPECT : Action.UPDATE_OPPORTUNITY;
        }
        require(actor, needed, id);
        if (u.version() != null && u.version() != a.getVersion()) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Account.class, id);
        }
        if (u.name() != null) a.setName(Normalizer.clean(u.name()).orElse(a.getName()));
        if (u.website() != null) { a.setWebsite(u.website()); a.setDomain(Normalizer.domain(u.website()).orElse(null)); }
        if (u.industryId() != null) a.setIndustryId(u.industryId());
        if (u.subIndustry() != null) a.setSubIndustry(u.subIndustry());
        if (u.cityId() != null) { audit.change("account", id, "city_id", a.getCityId(), u.cityId(), actor.userId()); a.setCityId(u.cityId()); }
        if (u.employeeCount() != null) a.setEmployeeCount(u.employeeCount());
        if (u.taxableEmployeeCount() != null) a.setTaxableEmployeeCount(u.taxableEmployeeCount());
        if (u.licensePotential() != null) {
            audit.change("account", id, "license_potential", a.getLicensePotential(), u.licensePotential(), actor.userId());
            a.setLicensePotential(u.licensePotential());
        }
        if (u.leadSourceId() != null) a.setLeadSourceId(u.leadSourceId());
        if (u.nextStep() != null) a.setNextStep(u.nextStep());
        if (u.nextStepDue() != null) a.setNextStepDue(u.nextStepDue());
        accounts.saveAndFlush(a);
        em.refresh(a);
        return view(a);
    }

    // ------------------------------------------------------------------ commands for other modules

    @Override
    @Transactional
    public void addBdr(UUID accountId, UUID bdrId) {
        jdbc.update("""
                INSERT INTO crm.account_bdr (account_id, bdr_id) VALUES (:a, :b) ON CONFLICT DO NOTHING""",
                Map.of("a", accountId, "b", bdrId));
    }

    @Override
    @Transactional
    public void moveBdr(UUID accountId, UUID fromBdr, UUID toBdr) {
        addBdr(accountId, toBdr);
        Integer left = jdbc.queryForObject("""
                SELECT count(*) FROM crm.stakeholder WHERE account_id = :a AND bdr_owner_id = :b""",
                Map.of("a", accountId, "b", fromBdr), Integer.class);
        if (left != null && left == 0) {
            jdbc.update("DELETE FROM crm.account_bdr WHERE account_id = :a AND bdr_id = :b", Map.of("a", accountId, "b", fromBdr));
        }
    }

    @Override
    @Transactional
    public void enterPipeline(UUID accountId, Instant at) {
        Account a = entity(accountId);
        if (a.getVisibilityZone() == Zone.PIPELINE) return;
        a.setVisibilityZone(Zone.PIPELINE);
        a.setPipelineSince(at);
    }

    @Override
    @Transactional
    public void leavePipeline(UUID accountId) {
        Integer opps = jdbc.queryForObject("SELECT count(*) FROM crm.opportunity WHERE account_id = :a",
                Map.of("a", accountId), Integer.class);
        if (opps != null && opps > 0) return; // once an opportunity exists the account stays in the shared zone
        Account a = entity(accountId);
        a.setVisibilityZone(Zone.PROSPECTING);
        a.setPipelineSince(null);
    }

    @Override
    @Transactional
    public void setSqlCredit(UUID accountId, UUID bdrId) {
        Account a = entity(accountId);
        a.setSqlCreditBdrId(bdrId);
        addBdr(accountId, bdrId);
        jdbc.update("UPDATE crm.account_bdr SET role = CASE WHEN bdr_id = :b THEN 'SOURCER' ELSE 'CONTRIBUTOR' END WHERE account_id = :a",
                Map.of("a", accountId, "b", bdrId));
    }

    @Override
    @Transactional
    public void setAeOwner(UUID accountId, UUID aeId) { entity(accountId).setAeOwnerId(aeId); }

    @Override
    @Transactional
    public void setIcpFit(UUID accountId, IcpFit fit) { entity(accountId).setIcpFit(fit); }

    @Override
    @Transactional
    public void setIntent(UUID accountId, IntentRating rating, String reason) {
        Account a = entity(accountId);
        a.setIntentRating(rating);
        a.setIntentReason(reason);
    }

    @Override
    @Transactional
    public void setNextStep(UUID accountId, String nextStep, LocalDate due) {
        Account a = entity(accountId);
        a.setNextStep(nextStep);
        a.setNextStepDue(due);
    }

    @Override
    @Transactional
    public void markInactive(UUID accountId, UUID reasonId, String remark, LocalDate revisitOn, Instant at) {
        Account a = entity(accountId);
        a.setAccountStatus(AccountStatus.INACTIVE);
        a.setInactiveReasonId(reasonId);
        a.setInactiveRemark(remark);
        a.setRevisitOn(revisitOn);
        a.setInactiveSince(at);
        a.setReviewFlaggedAt(null);
    }

    @Override
    @Transactional
    public void markActive(UUID accountId) {
        Account a = entity(accountId);
        a.setAccountStatus(AccountStatus.ACTIVE);
        a.setInactiveReasonId(null);
        a.setInactiveRemark(null);
        a.setInactiveSince(null);
        a.setRevisitOn(null);
        a.setReviewFlaggedAt(null);
    }

    @Override
    @Transactional
    public void flagForReview(UUID accountId, Instant at) { entity(accountId).setReviewFlaggedAt(at); }

    @Override
    @Transactional
    public void correctQualification(UUID accountId, Integer licensePotential, UUID cityId) {
        Account a = entity(accountId);
        if (licensePotential != null) a.setLicensePotential(licensePotential);
        if (cityId != null) a.setCityId(cityId);
        accounts.saveAndFlush(a);
        em.refresh(a);
    }

    // ------------------------------------------------------------------ guard

    @Override
    public AccountAccess access(UUID accountId) {
        Account a = entity(accountId);
        Set<UUID> bdrs = new HashSet<>(jdbc.queryForList("SELECT bdr_id FROM crm.account_bdr WHERE account_id = :a",
                Map.of("a", accountId), UUID.class));
        return new AccountAccess(accountId, a.getVisibilityZone(), a.getAeOwnerId(), bdrs);
    }

    @Override
    public boolean can(Actor actor, Action action, UUID accountId) {
        AccountAccess acc = access(accountId);
        Optional<String> override = config.permissionOverride(actor.role(), action.name(), acc.zone());
        if (override.isPresent()) {
            return switch (override.get()) {
                case "ALL" -> true;
                case "NONE" -> false;
                default -> acc.isBdrOn(actor.userId()) || acc.isAeOwner(actor.userId());
            };
        }
        return policy.can(actor, action, acc);
    }

    @Override
    public void require(Actor actor, Action action, UUID accountId) {
        if (!can(actor, action, accountId)) {
            throw new ForbiddenException("You cannot " + action.name().toLowerCase().replace('_', ' ') + " on this account");
        }
    }

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public AccountView get(UUID id) { return view(entity(id)); }

    Account entity(UUID id) { return accounts.findById(id).orElseThrow(() -> new NotFoundException("Account", id)); }

    static AccountView view(Account a) {
        return new AccountView(a.getId(), a.getName(), a.getDomain(), a.getWebsite(), a.getIndustryId(), a.getSubIndustry(),
                a.getCityId(), a.getEmployeeCount(), a.getTaxableEmployeeCount(), a.getLicensePotential(), a.sizeBucket(),
                a.getLeadSourceId(), a.getApolloId(), a.getAeOwnerId(), a.getSqlCreditBdrId(), a.getLifecycleStage(),
                a.getVisibilityZone(), a.getPipelineSince(), a.getAccountStatus(), a.getInactiveReasonId(),
                a.getInactiveRemark(), a.getInactiveSince(), a.getRevisitOn(), a.getIcpFit(), a.getIntentRating(),
                a.getIntentReason(), a.getNextStep(), a.getNextStepDue(), a.getLastActivityAt(), a.getReviewFlaggedAt(),
                a.getCreatedAt(), a.getVersion());
    }
}
