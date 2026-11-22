package com.prosperr.crm.shared.security;

/** Rows of the PRD "Users, roles and permissions" table. */
public enum Action {
    // Prospecting zone
    VIEW_PROSPECT, WORK_PROSPECT, EDIT_SQL, SUBMIT_SQL, REASSIGN_BDR,
    // Pipeline zone
    VIEW_PIPELINE, ADD_STAKEHOLDER, LOG_ACTIVITY, CORRECT_QUALIFICATION, UPDATE_ENGAGEMENT,
    UPDATE_MEETINGS, UPDATE_OPPORTUNITY, MOVE_INACTIVE, REACTIVATE, ASSIGN_AE,
    // Reports and settings
    VALIDATE_SQL, SET_TARGETS, MANAGE_SETTINGS, MANAGE_USERS, VIEW_ALL_REPORTS
}
