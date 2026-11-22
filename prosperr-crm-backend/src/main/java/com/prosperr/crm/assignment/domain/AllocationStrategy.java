package com.prosperr.crm.assignment.domain;

import java.util.List;

/** How to rank AEs for a new account (PRD M5-2). The manager always makes the final choice (M5-3). */
public interface AllocationStrategy {
    String name();
    List<AeLoad> rank(List<AeLoad> loads);
}
