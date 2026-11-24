package com.prosperr.crm.ai.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Small rules applied to what Sarvam reads off a card. */
class CardScanRulesTest {
    @Test
    void companyNamesInCapitalsAreTidiedButAcronymsStay() {
        assertEquals("Northwind Labs", CardScanService.tidyCompany("NORTHWIND LABS"));
        assertEquals("Sterling Foods Pvt. Ltd.", CardScanService.tidyCompany("STERLING FOODS PVT. LTD."));
        assertEquals("KPMG", CardScanService.tidyCompany("KPMG"));
        assertEquals("orbitpay", CardScanService.tidyCompany("orbitpay"));
    }

    @Test
    void functionComesFromTheTitle() {
        assertEquals("PAYROLL", CardScanService.department(null, "Head of Payroll"));
        assertEquals("FINANCE", CardScanService.department(null, "AVP Finance"));
        assertEquals("FINANCE", CardScanService.department(null, "Chief Financial Officer"));
        assertEquals("HR", CardScanService.department(null, "Director - People & Culture"));
        assertEquals("HR", CardScanService.department(null, "CHRO"));
        assertNull(CardScanService.department(null, "Founder"));
    }

    @Test
    void freeMailIsNotACompanyWebsite() {
        assertTrue(CardScanService.freeMail("rahul.k@gmail.com"));
        assertFalse(CardScanService.freeMail("rahul@acme.in"));
    }
}
