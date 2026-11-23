package com.prosperr.crm.importer.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Column auto-matching for the import wizard, on real header sets. */
class ImportSuggestTest {

    @Test
    void apolloPeopleExport() {
        Map<String, String> m = ImportService.suggest(List.of("First Name", "Last Name", "Title", "Company", "Company Name for Emails",
                "Email", "Email Status", "Seniority", "Departments", "Mobile Phone", "Corporate Phone", "# Employees", "Industry",
                "Person Linkedin Url", "Website", "City", "Apollo Account Id"));
        assertEquals("firstName", m.get("First Name"));
        assertEquals("lastName", m.get("Last Name"));
        assertEquals("designation", m.get("Title"));
        assertEquals("company", m.get("Company"));
        assertNull(m.get("Company Name for Emails"), "company is taken once");
        assertEquals("email", m.get("Email"));
        assertEquals("phone", m.get("Mobile Phone"));
        assertEquals("phone2", m.get("Corporate Phone"));
        assertEquals("employeeCount", m.get("# Employees"));
        assertEquals("linkedin", m.get("Person Linkedin Url"));
        assertEquals("website", m.get("Website"));
        assertEquals("city", m.get("City"));
        assertEquals("apolloId", m.get("Apollo Account Id"));
        assertEquals("department", m.get("Departments"));
        assertNull(m.get("Email Status"), "email status is not an email");
    }

    @Test
    void teamTrackingSheet() {
        Map<String, String> m = ImportService.suggest(List.of("AE", "Account Name", "BDR Name", "Industry", "Employee(s)", "Location",
                "License(s)", "POC Name", "Designation", "Phone Number", "Email ID", "Remarks"));
        assertEquals("company", m.get("Account Name"));
        assertEquals("employeeCount", m.get("Employee(s)"));
        assertEquals("city", m.get("Location"));
        assertEquals("licensePotential", m.get("License(s)"));
        assertEquals("fullName", m.get("POC Name"));
        assertEquals("designation", m.get("Designation"));
        assertEquals("phone", m.get("Phone Number"));
        assertEquals("email", m.get("Email ID"));
        assertEquals("remarks", m.get("Remarks"));
        assertNull(m.get("BDR Name"), "a BDR name is not an owner email");
    }

    @Test
    void singleNameColumnWinsOverLoneFirstName() {
        Map<String, String> m = ImportService.suggest(List.of("Name", "First Name", "Company"));
        assertEquals("fullName", m.get("Name"));
        assertFalse(m.containsValue("firstName"));
    }

    @Test
    void blankAndRepeatedHeadersGetNames() {
        assertEquals(List.of("Name", "Column B", "name (2)"), RowReader.cleanHeaders(java.util.Arrays.asList("﻿Name", "", "name")));
    }
}
