package com.prosperr.crm.qualification.domain;

import java.util.Map;

/** PRD M4: more than 250 licenses; Small accounts only as an exception with a reason and manager approval (M4-11). */
public final class LicensesCriterion implements IcpCriterion {
    public static final String CODE = "LICENSES";
    public String code() { return CODE; }

    public CriterionResult evaluate(QualificationInput in, Map<String, Object> cfg) {
        int min = ConfigValues.intValue(cfg, "min_licenses", 251);
        Integer lic = in.licensePotential();
        if (lic == null) return CriterionResult.fail(CODE, "License potential is missing");
        if (lic >= min) return CriterionResult.pass(CODE, lic + " licenses");
        if (!in.smallException()) return CriterionResult.fail(CODE, lic + " licenses is below " + min);
        if (in.smallExceptionReason() == null || in.smallExceptionReason().isBlank()) {
            return CriterionResult.fail(CODE, "Small exception needs a reason");
        }
        return in.smallApproved()
                ? CriterionResult.pass(CODE, "Small exception approved")
                : CriterionResult.approval(CODE, "Small exception waiting for manager approval");
    }
}
