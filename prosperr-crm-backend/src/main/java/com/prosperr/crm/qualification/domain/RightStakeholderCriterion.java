package com.prosperr.crm.qualification.domain;

import java.util.List;
import java.util.Map;

/** PRD M4: engaged L1/L2 directly, or L3–L5 when referred by an L1/L2 at the same account (M4-6). */
public final class RightStakeholderCriterion implements IcpCriterion {
    public static final String CODE = "RIGHT_STAKEHOLDER";
    public String code() { return CODE; }

    public CriterionResult evaluate(QualificationInput in, Map<String, Object> cfg) {
        List<Integer> direct = ConfigValues.ints(cfg, "direct_levels", List.of(1, 2));
        List<Integer> referred = ConfigValues.ints(cfg, "referred_levels", List.of(3, 4, 5));
        List<Integer> referrers = ConfigValues.ints(cfg, "referrer_levels", List.of(1, 2));
        Integer level = in.stakeholderLevel();
        if (level == null) return CriterionResult.fail(CODE, "Designation is not mapped to a level");
        if (!in.stakeholderEngaged()) return CriterionResult.fail(CODE, "Stakeholder is not engaged yet");
        if (direct.contains(level)) return CriterionResult.pass(CODE, "Level L" + level + " reached directly");
        if (referred.contains(level)) {
            if (in.referrerLevel() != null && in.referrerSameAccount() && referrers.contains(in.referrerLevel())) {
                return CriterionResult.pass(CODE, "Level L" + level + ", referred by an L" + in.referrerLevel());
            }
            return CriterionResult.fail(CODE, "Level L" + level + " needs a referral from an L1 or L2 at the same account");
        }
        return CriterionResult.fail(CODE, "Level L" + level + " does not qualify");
    }
}
