package com.prosperr.crm.shared.domain;

/** Values mirror the CHECK constraint in V1__init.sql. */
public enum AssignmentMethod {
    MANUAL,
    ROUND_ROBIN,
    CAPACITY,
    USER_DEACTIVATED,
    MIGRATION
}
