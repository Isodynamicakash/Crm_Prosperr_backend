package com.prosperr.crm.qualification.domain;

import com.prosperr.crm.shared.domain.IntentRating;
import com.prosperr.crm.shared.domain.LocationPriority;

import java.util.Map;

/** PRD M4 / M4-7: P0 cities pass; P1 (Chennai, Mumbai) need High intent + reason, confirmed by the manager. */
public final class LocationCriterion implements IcpCriterion {
    public static final String CODE = "LOCATION";
    public String code() { return CODE; }

    public CriterionResult evaluate(QualificationInput in, Map<String, Object> cfg) {
        LocationPriority p = in.locationPriority();
        if (p == null || p == LocationPriority.NONE) return CriterionResult.fail(CODE, "City is outside the ICP");
        if (p == LocationPriority.P0) return CriterionResult.pass(CODE, "P0 city");
        if (in.intentRating() != IntentRating.HIGH || in.intentReason() == null || in.intentReason().isBlank()) {
            return CriterionResult.fail(CODE, "P1 city needs High intent with a written reason");
        }
        return in.intentConfirmed()
                ? CriterionResult.pass(CODE, "P1 city, High intent confirmed")
                : CriterionResult.approval(CODE, "P1 city, High intent waiting for manager confirmation");
    }
}
