package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.prosperr.crm.identity.application.GoogleTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** PRD Users/roles, non-functional "Google sign-in on the company domain", M10-1, M5-5. */
class AuthAndAdminIT extends IntegrationTest {

    @Test
    void Login_adminCreatedUserSignsInWithGoogleAndGetsRoleFromCrm() throws Exception {
        when(google.verify("good")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-1", "jasim@prosperr.io", true, "prosperr.io"));
        JsonNode t = post("/api/auth/google", null, Map.of("idToken", "good"));
        assertEquals("BDR", t.path("user").path("role").asText());
        String bearer = t.path("accessToken").asText();
        var res = mvc.perform(MockMvcRequestBuilders.get("/api/auth/me").header("Authorization", "Bearer " + bearer)).andReturn();
        assertEquals(200, res.getResponse().getStatus());
        assertEquals("g-1", db.queryForObject("SELECT google_sub FROM crm.app_user WHERE id = ?", String.class, bdr1));
    }

    @Test
    void Login_unknownWrongDomainOrDeactivatedUsersAreRefused() throws Exception {
        when(google.verify("stranger")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-2", "new.person@prosperr.io", true, "prosperr.io"));
        post("/api/auth/google", null, Map.of("idToken", "stranger"), 403);
        when(google.verify("gmail")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-3", "jasim@gmail.com", true, null));
        post("/api/auth/google", null, Map.of("idToken", "gmail"), 403);
        post("/api/admin/users/" + bdr2 + "/deactivate", admin, null);
        when(google.verify("gone")).thenReturn(new GoogleTokenVerifier.GoogleIdentity("g-4", "paramita@prosperr.io", true, "prosperr.io"));
        post("/api/auth/google", null, Map.of("idToken", "gone"), 403);
    }

    @Test
    void Security_apiNeedsToken() throws Exception {
        assertEquals(401, mvc.perform(MockMvcRequestBuilders.get("/api/accounts")).andReturn().getResponse().getStatus());
    }

    @Test
    void M10_1_onlyAdminCreatesUsersAndDecidesRole() throws Exception {
        Map<String, Object> body = Map.of("email", "suneeth@prosperr.io", "fullName", "Suneeth K", "role", "BDR");
        post("/api/admin/users", bdr1, body, 403);
        post("/api/admin/users", manager, body, 403);
        JsonNode u = post("/api/admin/users", admin, body);
        assertEquals("BDR", u.path("role").asText());
        patch("/api/admin/users/" + u.path("id").asText(), admin, Map.of("role", "AE"));
        assertEquals("AE", db.queryForObject("SELECT role FROM crm.app_user WHERE email = 'suneeth@prosperr.io'", String.class));
    }

    @Test
    void M5_5_deactivatingUserListsAllTheirAccountsForReassignment() throws Exception {
        var q = qualifiedAccount("Acme Corp", 600, ae1);
        JsonNode owned = post("/api/admin/users/" + ae1 + "/deactivate", admin, null);
        assertEquals(1, owned.size());
        assertEquals(q.get("account").toString(), owned.get(0).path("accountId").asText());
        post("/api/assignment/bulk", manager, Map.of("fromUserId", ae1, "moves",
                java.util.List.of(Map.of("accountId", q.get("account"), "toUserId", ae2))));
        assertEquals(ae2, db.queryForObject("SELECT ae_owner_id FROM crm.account WHERE id = ?", UUID.class, q.get("account")));
    }

    @Test
    void M10_1_adminEditsSettingsPicklistsAndPermissionOverrides() throws Exception {
        put("/api/admin/picklists", admin, Map.of("key", "INACTIVE_REASON", "code", "MERGER", "label", "Company merged",
                "requiresRevisitDate", true));
        assertNotNull(pick("INACTIVE_REASON", "MERGER"));
        put("/api/admin/stage-thresholds/L1", admin, Map.of("inactivityFlagDays", 45, "stagnationDays", 20));
        assertEquals(45, db.queryForObject("SELECT inactivity_flag_days FROM crm.stage_threshold WHERE stage = 'L1'", Integer.class));
        put("/api/admin/stage-thresholds/L1", admin, Map.of("inactivityFlagDays", 60, "stagnationDays", 30));
        // permission override: AEs may reactivate (default: No)
        put("/api/admin/permissions", admin, Map.of("role", "AE", "action", "REACTIVATE", "zone", "ANY", "scope", "OWN"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.role_field_permission", Integer.class));
    }

    @Test
    void Admin_cannotLockEveryoneOut_andDeactivationCutsAccessAtOnce() throws Exception {
        post("/api/admin/users/" + admin + "/deactivate", admin, null, 422);                   // not yourself
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/admin/users/" + admin), admin, Map.of("role", "MANAGER"), 422); // would leave no admin
        assertEquals("ADMIN", db.queryForObject("SELECT role FROM crm.app_user WHERE id = ?", String.class, admin));
        get("/api/my-day", bdr2);                                                              // token works
        post("/api/admin/users/" + bdr2 + "/deactivate", admin, null);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/my-day"), bdr2, null, 401);
        get("/api/my-day", bdr1);                                                              // token issued as BDR
        patch("/api/admin/users/" + bdr1, admin, Map.of("role", "AE"));                         // role change: old token stops
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/my-day"), bdr1, null, 401);
    }
}
