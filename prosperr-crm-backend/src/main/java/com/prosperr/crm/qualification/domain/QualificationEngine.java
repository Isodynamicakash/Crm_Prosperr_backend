package com.prosperr.crm.qualification.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Runs the active ICP rules (Strategy + Specification). An account is an SQL only when every active rule passes. */
public final class QualificationEngine {

    public record ActiveRule(String code, Map<String, Object> config) {}

    public record Result(List<CriterionResult> results) {
        public boolean canSubmit() { return results.stream().allMatch(CriterionResult::passed); }
        public boolean needsApproval() { return results.stream().anyMatch(r -> r.status() == CriterionStatus.NEEDS_APPROVAL); }
        public boolean fullyQualified() { return results.stream().allMatch(r -> r.status() == CriterionStatus.PASS); }
    }

    private final Map<String, IcpCriterion> criteria;

    public QualificationEngine(List<IcpCriterion> criteria) {
        this.criteria = criteria.stream().collect(Collectors.toMap(IcpCriterion::code, Function.identity()));
    }

    public static QualificationEngine standard() {
        return new QualificationEngine(List.of(new RightStakeholderCriterion(), new LicensesCriterion(),
                new LocationCriterion(), new BantCriterion()));
    }

    public Result evaluate(QualificationInput in, List<ActiveRule> rules) {
        return new Result(rules.stream().map(r -> {
            IcpCriterion c = criteria.get(r.code());
            if (c == null) return CriterionResult.fail(r.code(), "No implementation for rule " + r.code());
            return c.evaluate(in, r.config());
        }).toList());
    }
}
