package com.prosperr.crm.shared.domain;

/** Values mirror the CHECK constraint in V1__init.sql. */
public enum TaskSource {
    MANUAL,
    CALL_RULE,
    SEQUENCE,
    SYSTEM
}
