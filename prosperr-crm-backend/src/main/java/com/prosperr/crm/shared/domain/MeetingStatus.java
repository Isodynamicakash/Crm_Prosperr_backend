package com.prosperr.crm.shared.domain;

/** Values mirror the CHECK constraint in V1__init.sql. */
public enum MeetingStatus {
    SCHEDULED,
    RESCHEDULED,
    COMPLETED,
    NO_SHOW,
    CANCELLED
}
