package com.prosperr.crm.account.api;

import com.prosperr.crm.shared.domain.IcpFit;
import com.prosperr.crm.shared.domain.IntentRating;
import com.prosperr.crm.shared.security.Actor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Account commands and queries for other modules. */
public interface AccountApi {

    record CreateAccount(String name, String website, UUID industryId, String subIndustry, UUID cityId,
                         Integer employeeCount, Integer taxableEmployeeCount, Integer licensePotential,
                         UUID leadSourceId, String apolloId) {}

    /** Name, designation and owning BDR of stakeholders another BDR already works (M2-9). */
    record TakenStakeholder(String name, String designation, String bdrName) {}

    record Resolution(AccountView account, boolean created, List<TakenStakeholder> alreadyTaken) {}

    AccountView get(UUID id);

    /** Creates the account, or joins the existing one when name or domain match (PRD duplicate rules, M2-9). */
    Resolution resolveOrCreate(CreateAccount cmd, Actor actor);

    void addBdr(UUID accountId, UUID bdrId);
    void moveBdr(UUID accountId, UUID fromBdr, UUID toBdr);
    void enterPipeline(UUID accountId, Instant at);
    void leavePipeline(UUID accountId);
    void setSqlCredit(UUID accountId, UUID bdrId);
    void setAeOwner(UUID accountId, UUID aeId);
    void setIcpFit(UUID accountId, IcpFit fit);
    void setIntent(UUID accountId, IntentRating rating, String reason);
    void setNextStep(UUID accountId, String nextStep, LocalDate due);
    void markInactive(UUID accountId, UUID reasonId, String remark, LocalDate revisitOn, Instant at);
    void markActive(UUID accountId);
    void flagForReview(UUID accountId, Instant at);
    void correctQualification(UUID accountId, Integer licensePotential, UUID cityId);
}
