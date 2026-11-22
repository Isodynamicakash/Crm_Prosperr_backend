package com.prosperr.crm.shared.security;

import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.domain.Zone;

/**
 * The PRD permission table as code. Two visibility zones: Prospecting (before L1 is booked) is private
 * to the BDRs working the account plus leadership; Pipeline (from L1 booking) is visible to everyone,
 * and edit rights depend on role and ownership. Manager and Admin share the "Manager / Leadership / Admin" column.
 */
public final class AccessPolicy {

    /** Account-independent actions (settings, assignment, reports). */
    public boolean can(Actor actor, Action action) {
        return switch (action) {
            case ASSIGN_AE, REASSIGN_BDR, VALIDATE_SQL, SET_TARGETS, VIEW_ALL_REPORTS -> actor.isLeadership();
            case MANAGE_SETTINGS, MANAGE_USERS -> actor.isLeadership();
            // creating a brand-new prospect: BDRs (own) and leadership; AEs cannot
            case WORK_PROSPECT -> actor.role() == Role.BDR || actor.isLeadership();
            default -> false;
        };
    }

    public boolean can(Actor actor, Action action, AccountAccess acc) {
        if (actor.isLeadership()) return true;
        Role r = actor.role();
        boolean bdrOn = acc.isBdrOn(actor.userId());
        boolean aeOwner = acc.isAeOwner(actor.userId());

        if (acc.zone() == Zone.PROSPECTING) {
            return switch (action) {
                case VIEW_PROSPECT, VIEW_PIPELINE, WORK_PROSPECT, EDIT_SQL, SUBMIT_SQL,
                     ADD_STAKEHOLDER, LOG_ACTIVITY, UPDATE_ENGAGEMENT -> r == Role.BDR && bdrOn;
                // M8-6: BDRs revive inactive accounts, including ones that never reached L1
                case REACTIVATE -> r == Role.BDR && bdrOn;
                default -> false;
            };
        }
        // Pipeline zone
        return switch (action) {
            case VIEW_PIPELINE, VIEW_PROSPECT, ADD_STAKEHOLDER, LOG_ACTIVITY -> true;
            case UPDATE_ENGAGEMENT -> (r == Role.BDR && bdrOn) || (r == Role.AE && aeOwner);
            case CORRECT_QUALIFICATION, UPDATE_MEETINGS, UPDATE_OPPORTUNITY, MOVE_INACTIVE -> r == Role.AE && aeOwner;
            case REACTIVATE -> r == Role.BDR;
            // a BDR joining an account that is already in the pipeline (M2-9) can still work their own stakeholders
            case WORK_PROSPECT -> r == Role.BDR;
            default -> false;
        };
    }

    /**
     * PRD M7-7 and decision #13: prospecting touches are shown only to the BDR who logged them and leadership,
     * unless the account has reached the pipeline and the "history visible after L1" setting is on.
     */
    public boolean canSeeActivity(Actor actor, Zone activityZone, java.util.UUID loggedBy, Zone accountZone,
                                  boolean historyVisibleAfterL1) {
        if (activityZone == Zone.PIPELINE || actor.isLeadership()) return true;
        if (actor.userId().equals(loggedBy)) return true;
        return accountZone == Zone.PIPELINE && historyVisibleAfterL1;
    }
}
