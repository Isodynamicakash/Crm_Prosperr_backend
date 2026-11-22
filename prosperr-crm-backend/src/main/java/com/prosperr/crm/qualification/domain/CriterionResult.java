package com.prosperr.crm.qualification.domain;

public record CriterionResult(String code, CriterionStatus status, String detail) {
    public static CriterionResult pass(String code, String detail) { return new CriterionResult(code, CriterionStatus.PASS, detail); }
    public static CriterionResult fail(String code, String detail) { return new CriterionResult(code, CriterionStatus.FAIL, detail); }
    public static CriterionResult approval(String code, String detail) { return new CriterionResult(code, CriterionStatus.NEEDS_APPROVAL, detail); }
    public boolean passed() { return status != CriterionStatus.FAIL; }
}
