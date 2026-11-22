package com.prosperr.crm.shared.security;

import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.domain.Zone;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD "Users, roles and permissions" table, row by row. */
class AccessPolicyTest {
    final AccessPolicy p = new AccessPolicy();
    final UUID bdr1 = UUID.randomUUID(), bdr2 = UUID.randomUUID(), ae1 = UUID.randomUUID(), ae2 = UUID.randomUUID();
    final Actor BDR1 = new Actor(bdr1, Role.BDR), BDR2 = new Actor(bdr2, Role.BDR),
            AE1 = new Actor(ae1, Role.AE), AE2 = new Actor(ae2, Role.AE),
            MGR = new Actor(UUID.randomUUID(), Role.MANAGER), ADMIN = new Actor(UUID.randomUUID(), Role.ADMIN);
    final AccountAccess prospect = new AccountAccess(UUID.randomUUID(), Zone.PROSPECTING, null, Set.of(bdr1));
    final AccountAccess pipeline = new AccountAccess(UUID.randomUUID(), Zone.PIPELINE, ae1, Set.of(bdr1));

    @Test
    void M2_8_prospectVisibleOnlyToOwningBdrAndLeadership() {
        assertTrue(p.can(BDR1, Action.VIEW_PROSPECT, prospect));
        assertFalse(p.can(BDR2, Action.VIEW_PROSPECT, prospect));
        assertFalse(p.can(AE1, Action.VIEW_PROSPECT, prospect));
        assertTrue(p.can(MGR, Action.VIEW_PROSPECT, prospect));
        assertTrue(p.can(ADMIN, Action.VIEW_PROSPECT, prospect));
    }

    @Test
    void Perm_prospecting_createLogSequencesAndSqlOwnOnly() {
        for (Action a : new Action[]{Action.WORK_PROSPECT, Action.EDIT_SQL, Action.SUBMIT_SQL, Action.LOG_ACTIVITY}) {
            assertTrue(p.can(BDR1, a, prospect), a.name());
            assertFalse(p.can(BDR2, a, prospect), a.name());
            assertFalse(p.can(AE1, a, prospect), a.name());
            assertTrue(p.can(MGR, a, prospect), a.name());
        }
        assertFalse(p.can(AE1, Action.WORK_PROSPECT), "AEs cannot create prospects");
        assertTrue(p.can(BDR1, Action.WORK_PROSPECT));
    }

    @Test
    void Perm_reassignProspectAndAssignAeOnlyLeadership() {
        assertFalse(p.can(BDR1, Action.REASSIGN_BDR));
        assertFalse(p.can(AE1, Action.ASSIGN_AE));
        assertFalse(p.can(BDR1, Action.ASSIGN_AE, pipeline));
        assertTrue(p.can(MGR, Action.ASSIGN_AE));
        assertTrue(p.can(MGR, Action.REASSIGN_BDR));
    }

    @Test
    void Perm_pipelineVisibleToEveryoneFromL1() {
        for (Actor a : new Actor[]{BDR1, BDR2, AE1, AE2, MGR}) {
            assertTrue(p.can(a, Action.VIEW_PIPELINE, pipeline));
            assertTrue(p.can(a, Action.ADD_STAKEHOLDER, pipeline));
            assertTrue(p.can(a, Action.LOG_ACTIVITY, pipeline));
        }
    }

    @Test
    void Perm_pipeline_meetingsStageInactiveAndCorrectionsOwnAeOnly() {
        for (Action a : new Action[]{Action.UPDATE_MEETINGS, Action.UPDATE_OPPORTUNITY, Action.MOVE_INACTIVE,
                Action.CORRECT_QUALIFICATION}) {
            assertTrue(p.can(AE1, a, pipeline), a.name());
            assertFalse(p.can(AE2, a, pipeline), a.name());
            assertFalse(p.can(BDR1, a, pipeline), a.name());
            assertTrue(p.can(MGR, a, pipeline), a.name());
        }
    }

    @Test
    void Perm_engagement_bdrOnSourcedAccounts_aeOnOwn() {
        assertTrue(p.can(BDR1, Action.UPDATE_ENGAGEMENT, pipeline));
        assertFalse(p.can(BDR2, Action.UPDATE_ENGAGEMENT, pipeline));
        assertTrue(p.can(AE1, Action.UPDATE_ENGAGEMENT, pipeline));
        assertFalse(p.can(AE2, Action.UPDATE_ENGAGEMENT, pipeline));
    }

    @Test
    void Perm_reactivate_bdrYes_aeNo() {
        assertTrue(p.can(BDR2, Action.REACTIVATE, pipeline));
        assertFalse(p.can(AE1, Action.REACTIVATE, pipeline));
        assertTrue(p.can(MGR, Action.REACTIVATE, pipeline));
    }

    @Test
    void Perm_settingsTargetsUsersOnlyLeadership() {
        for (Action a : new Action[]{Action.SET_TARGETS, Action.MANAGE_SETTINGS, Action.MANAGE_USERS, Action.VALIDATE_SQL}) {
            assertFalse(p.can(BDR1, a), a.name());
            assertFalse(p.can(AE1, a), a.name());
            assertTrue(p.can(ADMIN, a), a.name());
        }
    }

    @Test
    void M7_7_prospectingTouchesHiddenFromOthers() {
        assertFalse(p.canSeeActivity(AE1, Zone.PROSPECTING, bdr1, Zone.PROSPECTING, true));
        assertTrue(p.canSeeActivity(BDR1, Zone.PROSPECTING, bdr1, Zone.PROSPECTING, true));
        assertTrue(p.canSeeActivity(MGR, Zone.PROSPECTING, bdr1, Zone.PROSPECTING, false));
        assertTrue(p.canSeeActivity(AE1, Zone.PIPELINE, bdr1, Zone.PIPELINE, false));
        // decision #13: once the account is in the pipeline its prospecting history can be shown to the AE
        assertTrue(p.canSeeActivity(AE1, Zone.PROSPECTING, bdr1, Zone.PIPELINE, true));
        assertFalse(p.canSeeActivity(AE1, Zone.PROSPECTING, bdr1, Zone.PIPELINE, false));
    }
}
