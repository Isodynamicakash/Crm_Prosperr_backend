package com.prosperr.crm.shared.domain;

/** SQL qualification statuses (BRD 4.3). */
public enum SqlStatus {
    PENDING_QUALIFICATION,
    QUALIFIED_SQL,
    REJECTED_NOT_ICP,
    PENDING_SENIOR_CONNECTION,
    MEETING_BOOKED,
    NO_SHOW,
    DISQUALIFIED
}
