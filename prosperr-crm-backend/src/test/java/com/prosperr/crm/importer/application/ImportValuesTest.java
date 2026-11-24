package com.prosperr.crm.importer.application;

import com.prosperr.crm.shared.domain.Department;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The small differences people type in sheets still land on the CRM's own values. */
class ImportValuesTest {
    @Test
    void citySpellings() {
        assertEquals("Bengaluru", ImportValues.city("Bangalore"));
        assertEquals("Bengaluru", ImportValues.city("BANGALORE, Karnataka, India"));
        assertEquals("Gurugram", ImportValues.city("Gurgaon (Haryana)"));
        assertEquals("Mumbai", ImportValues.city("Navi Mumbai"));
        assertEquals("Delhi", ImportValues.city("New Delhi"));
        assertEquals("Hyderabad", ImportValues.city("Secunderabad"));
        assertEquals("Pune", ImportValues.city("pune"));
    }

    @Test
    void industriesAndSources() {
        assertEquals("IT services", ImportValues.industry("Information Technology & Services"));
        assertEquals("BFSI", ImportValues.industry("Fintech"));
        assertEquals("Healthcare", ImportValues.industry("Hospital & Health Care"));
        assertEquals("SaaS / software", ImportValues.industry("Computer Software"));
        assertEquals("Retail / e-commerce", ImportValues.industry("E-commerce"));
        assertEquals("Retail / e-commerce", ImportValues.industry("Consumer Products"));
        assertEquals("Space tech", ImportValues.industry("Space tech"), "unknown stays as typed so the preview can flag it");
        assertEquals("Apollo", ImportValues.leadSource("Apollo.io"));
        assertEquals("Event", ImportValues.leadSource("HR Summit conference"));
        assertEquals("Inbound", ImportValues.leadSource("Website form"));
        assertEquals("LinkedIn", ImportValues.leadSource("Sales Navigator"));
    }

    @Test
    void functions() {
        assertEquals(Department.HR, ImportValues.department("Human Resources"));
        assertEquals(Department.FINANCE, ImportValues.department("Finance & Accounts"));
        assertEquals(Department.PAYROLL, ImportValues.department("Payroll Ops"));
        assertEquals(Department.HR, ImportValues.department("hr"));
        assertNull(ImportValues.department("Sales"), "unknown: the job title decides");
    }

    @Test
    void numbers() {
        assertEquals(1200, ImportValues.number("1,200"));
        assertEquals(1200, ImportValues.number("1.2k"));
        assertEquals(501, ImportValues.number("501-1,000"));
        assertEquals(10000, ImportValues.number("10,000+"));
        assertEquals(250000, ImportValues.number("2.5 lakh"));
        assertNull(ImportValues.number("not sure"));
    }
}
