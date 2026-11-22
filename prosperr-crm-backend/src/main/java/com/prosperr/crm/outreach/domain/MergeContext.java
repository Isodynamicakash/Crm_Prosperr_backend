package com.prosperr.crm.outreach.domain;

import com.prosperr.crm.shared.domain.Department;

/** Values for template merge fields (PRD Appendix A). */
public record MergeContext(String firstName, String company, String designation, String senderFirstName,
                           String senderFullName, String senderPhone, String productLink, String stakeholderLink,
                           String callTaskDue, Department department, boolean startedFromCall) {}
