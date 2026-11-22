package com.prosperr.crm.shared.domain;

/** Values mirror the CHECK constraint in V1__init.sql. */
public enum StopReason {
    REPLY,
    BOUNCE,
    OPT_OUT,
    L1_BOOKED,
    MANUAL,
    COMPLETED
}
