package com.prosperr.crm.account.domain;

import com.prosperr.crm.shared.domain.Department;
import com.prosperr.crm.shared.domain.EngagementStatus;

import java.util.UUID;

/** Read-only view of a stakeholder used by the multithreading rules. */
public record StakeholderSnapshot(UUID id, String name, Department department, Integer level, int priorityRank,
                                  EngagementStatus status, boolean doNotContact, boolean primarySpoc,
                                  boolean decisionMaker) {}
