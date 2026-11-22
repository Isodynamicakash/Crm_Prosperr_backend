package com.prosperr.crm.qualification.domain;

import java.util.Map;

/** One ICP rule (PRD M4-2). Add a new criterion by adding a class; config comes from crm.icp_rule.config. */
public interface IcpCriterion {
    String code();
    CriterionResult evaluate(QualificationInput in, Map<String, Object> config);
}
