package com.prosperr.crm.it;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PRD M1, M2-8, M2-9, duplicate rules. */
class AccountsIT extends IntegrationTest {

    @Test
    void M1_4_sizeBucketComputedFromLicensePotential() throws Exception {
        UUID a = account(bdr1, "ABC Technologies", "https://www.abctech.com", "Bengaluru", 620);
        assertEquals("LARGE", db.queryForObject("SELECT size_bucket_code FROM crm.account WHERE id = ?", String.class, a));
        patch("/api/accounts/" + a, bdr1, Map.of("licensePotential", 200));
        assertEquals("SMALL", db.queryForObject("SELECT size_bucket_code FROM crm.account WHERE id = ?", String.class, a));
    }

    @Test
    void Duplicates_M2_9_sameCompanyJoinsExistingAccountAndShowsWhoIsTaken() throws Exception {
        UUID a = account(bdr1, "ABC Technologies", "abctech.com", "Bengaluru", 620);
        stakeholder(bdr1, a, "Rita Sharma", "CFO", "rita@abctech.com", null, null);
        JsonNode join = post("/api/accounts", bdr2, Map.of("name", "abc  technologies", "website", "https://abctech.com/about"));
        assertFalse(join.path("created").asBoolean());
        assertEquals(a.toString(), join.path("account").path("id").asText());
        JsonNode taken = join.path("alreadyTaken").get(0);
        assertEquals("Rita Sharma", taken.path("name").asText());
        assertEquals("CFO", taken.path("designation").asText());
        assertEquals("Jasim Test", taken.path("bdrName").asText());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.account", Integer.class));
        // the second BDR sees the first BDR's stakeholder masked: no contact details or history
        JsonNode list = get("/api/accounts/" + a + "/stakeholders", bdr2);
        assertTrue(list.get(0).path("masked").asBoolean());
        assertTrue(list.get(0).path("emails").isEmpty());
    }

    @Test
    void Duplicates_samePersonIsBlockedNamingTheOwner() throws Exception {
        UUID a = account(bdr1, "ABC Technologies", "abctech.com", "Bengaluru", 620);
        stakeholder(bdr1, a, "Rita Sharma", "CFO", "rita@abctech.com", "98765 43210", null);
        post("/api/accounts", bdr2, Map.of("name", "ABC Technologies"));
        JsonNode err = post("/api/accounts/" + a + "/stakeholders", bdr2,
                Map.of("fullName", "R Sharma", "emails", List.of("RITA@abctech.com")), 422);
        assertEquals("STAKEHOLDER_EXISTS", err.path("code").asText());
        assertTrue(err.path("detail").asText().contains("Jasim"));
        JsonNode phone = post("/api/accounts/" + a + "/stakeholders", bdr2,
                Map.of("fullName", "Someone", "phones", List.of("+91 98765-43210")), 422);
        assertEquals("STAKEHOLDER_EXISTS", phone.path("code").asText());
    }

    @Test
    void M2_8_prospectsArePrivateToTheirBdrAndLeadership() throws Exception {
        UUID a = account(bdr1, "Private Co", "private.co", "Bengaluru", 600);
        get("/api/accounts/" + a, bdr1);
        get("/api/accounts/" + a, manager);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/accounts/" + a), bdr2, null, 403);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/accounts/" + a), ae1, null, 403);
        assertEquals(0, get("/api/accounts", bdr2).path("total").asInt());
        assertEquals(1, get("/api/accounts", bdr1).path("total").asInt());
        // AEs cannot create prospects
        post("/api/accounts", ae1, Map.of("name", "Nope"), 403);
    }

    @Test
    void M1_3_manyEmailsAndPhones_markNumberInvalidWithoutClosingStakeholder() throws Exception {
        UUID a = account(bdr1, "Multi Co", "multi.co", "Bengaluru", 600);
        JsonNode s = stakeholder(bdr1, a, "Asha Rao", "CHRO", "asha@multi.co", "9876500001", null);
        UUID sid = UUID.fromString(s.path("id").asText());
        post("/api/stakeholders/" + sid + "/emails", bdr1, Map.of("email", "asha.rao@gmail.com"));
        JsonNode after = post("/api/stakeholders/" + sid + "/phones", bdr1, Map.of("phone", "9876500002", "whatsapp", true));
        assertEquals(2, after.path("emails").size());
        assertEquals(2, after.path("phones").size());
        String phoneId = after.path("phones").get(0).path("id").asText();
        post("/api/phones/" + phoneId + "/invalid", bdr1, null);
        assertEquals(1, db.queryForObject("SELECT count(*) FROM crm.stakeholder_phone WHERE stakeholder_id = ? AND is_valid", Integer.class, sid));
        assertEquals("NOT_CONTACTED", db.queryForObject("SELECT engagement_status FROM crm.stakeholder WHERE id = ?", String.class, sid));
    }

    @Test
    void M1_5_levelFromDesignationMap_unmappedGoesToReviewThenBackfilled() throws Exception {
        UUID a = account(bdr1, "Levels Co", "levels.co", "Bengaluru", 600);
        JsonNode cfo = stakeholder(bdr1, a, "C F O", "CFO", null, null, null);
        assertEquals(1, cfo.path("level").asInt());
        assertEquals("FINANCE", cfo.path("department").asText());
        JsonNode odd = stakeholder(bdr1, a, "Odd Title", "Chief Happiness Officer", null, null, null);
        assertTrue(odd.path("level").isNull());
        JsonNode review = get("/api/admin/designations/review", admin);
        assertEquals("chief happiness officer", review.get(0).path("designation").asText());
        put("/api/admin/designations", admin, Map.of("designation", "Chief Happiness Officer", "level", 2, "department", "HR",
                "priorityRank", 4, "rightPoc", true));
        assertEquals(2, db.queryForObject("SELECT level FROM crm.stakeholder WHERE id = ?::uuid", Integer.class, odd.path("id").asText()));
    }

    @Test
    void M1_6_globalSearchByCompanyPersonEmailPhoneLinkedIn() throws Exception {
        UUID a = account(bdr1, "Searchable Pvt Ltd", "searchable.in", "Bengaluru", 600);
        stakeholder(bdr1, a, "Kiran Mehta", "CFO", "kiran@searchable.in", "9123456789", null);
        assertEquals(1, get("/api/search?q=searchable", bdr1).path("accounts").size());
        assertEquals(1, get("/api/search?q=kiran@", bdr1).path("stakeholders").size());
        // MockMvc encodes the URL template itself, so pass the space raw (a pre-encoded %20 would arrive as the text "%20").
        assertEquals(1, get("/api/search?q=91234 56789", bdr1).path("stakeholders").size());
        assertEquals(0, get("/api/search?q=searchable", bdr2).path("accounts").size(), "prospects stay private in search");
        post("/api/saved-views", bdr1, Map.of("name", "My Large", "entity", "ACCOUNT", "filters", "{\"sizeBucket\":\"LARGE\"}"));
        assertEquals(1, get("/api/saved-views", bdr1).size());
    }

    @Test
    void M1_2_accountPageHasOwnersStakeholdersMeetingsOpportunityTimeline() throws Exception {
        var q = qualifiedAccount("Page Co", 700, ae1);
        JsonNode page = get("/api/accounts/" + q.get("account"), ae1);
        assertEquals("Page Co", page.path("account").path("name").asText());
        assertTrue(page.path("owners").size() >= 2);
        assertEquals(1, page.path("stakeholdersByDepartment").path("FINANCE").size());
        assertEquals(1, page.path("meetings").size());
        assertEquals("SQL", page.path("opportunity").path("stage").asText());
        assertFalse(page.path("timeline").isEmpty());
    }

    @Test
    void Concurrency_secondEditWithStaleVersionIsRefused() throws Exception {
        UUID a = account(bdr1, "Race Co", "race.co", "Bengaluru", 600);
        int v = db.queryForObject("SELECT version FROM crm.account WHERE id = ?", Integer.class, a);
        patch("/api/accounts/" + a, bdr1, Map.of("employeeCount", 900, "version", v));
        JsonNode err = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/accounts/" + a), bdr1,
                Map.of("employeeCount", 950, "version", v), 409);
        assertEquals("EDIT_CONFLICT", err.path("code").asText());
    }
}
