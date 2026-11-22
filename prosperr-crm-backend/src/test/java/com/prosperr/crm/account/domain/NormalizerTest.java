package com.prosperr.crm.account.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class NormalizerTest {

    @Test
    void M1_dedupe_companyNameMatchesDbGeneratedColumn() {
        assertEquals("abc technologies", Normalizer.companyName("  ABC   Technologies "));
    }

    @Test
    void M1_dedupe_domainFromWebsite() {
        assertEquals(Optional.of("abctech.com"), Normalizer.domain("https://www.ABCtech.com/about?x=1"));
        assertEquals(Optional.of("abctech.com"), Normalizer.domain("abctech.com"));
        assertEquals(Optional.empty(), Normalizer.domain("NA"));
    }

    @Test
    void M1_dedupe_emailLowercased() {
        assertEquals(Optional.of("rita@abctech.com"), Normalizer.email(" Rita@ABCtech.com "));
        assertEquals(Optional.empty(), Normalizer.email("not-an-email"));
        assertEquals(Optional.of("abctech.com"), Normalizer.emailDomain("Rita@ABCtech.com"));
    }

    @Test
    void M1_3_phoneToE164() {
        assertEquals(Optional.of("+919876543210"), Normalizer.phoneE164("98765 43210"));
        assertEquals(Optional.of("+919876543210"), Normalizer.phoneE164("09876543210"));
        assertEquals(Optional.of("+919876543210"), Normalizer.phoneE164("91-98765-43210"));
        assertEquals(Optional.of("+14155552671"), Normalizer.phoneE164("+1 415 555 2671"));
        assertEquals(Optional.empty(), Normalizer.phoneE164("12345"));
        assertEquals(Optional.empty(), Normalizer.phoneE164("NA"));
    }

    @Test
    void Migration_linkedInParametersStripped() {
        assertEquals(Optional.of("https://www.linkedin.com/in/rita-sharma"),
                Normalizer.linkedIn("linkedin.com/in/Rita-Sharma/?utm_source=share&trk=x"));
        assertEquals(Optional.empty(), Normalizer.linkedIn("https://example.com/in/rita"));
    }

    @Test
    void Migration_naBecomesEmptyAndNamesSplit() {
        assertTrue(Normalizer.clean("NA").isEmpty());
        assertTrue(Normalizer.clean("#N/A").isEmpty());
        String[] n = Normalizer.splitName("Rita  Kumari Sharma");
        assertEquals("Rita", n[0]);
        assertEquals("Kumari Sharma", n[1]);
        assertNull(Normalizer.splitName("Rita")[1]);
    }
}
