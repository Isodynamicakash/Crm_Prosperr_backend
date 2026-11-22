package com.prosperr.crm.qualification.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PRD M4 / M4-10: Budget, Authority, Need and Timeline must all be recorded. */
public final class BantCriterion implements IcpCriterion {
    public static final String CODE = "BANT";
    public String code() { return CODE; }

    public CriterionResult evaluate(QualificationInput in, Map<String, Object> cfg) {
        List<String> missing = new ArrayList<>();
        if (!in.hasBudget()) missing.add("Budget");
        if (!in.hasAuthority()) missing.add("Authority");
        if (!in.hasNeed()) missing.add("Need");
        if (!in.hasTimeline()) missing.add("Timeline");
        return missing.isEmpty() ? CriterionResult.pass(CODE, "BANT complete")
                : CriterionResult.fail(CODE, "Missing: " + String.join(", ", missing));
    }
}
