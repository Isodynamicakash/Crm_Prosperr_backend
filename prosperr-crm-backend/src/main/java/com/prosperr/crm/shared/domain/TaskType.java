package com.prosperr.crm.shared.domain;

/** Values mirror the CHECK constraint in V1__init.sql. */
public enum TaskType {
    CALL,
    FOLLOW_UP,
    SEND_TEMPLATE,
    FIND_NUMBER,
    RESCHEDULE_MEETING,
    RECORD_OUTCOME,
    REVIVAL,
    REVIEW_INACTIVE,
    MULTITHREAD,
    OTHER
}
