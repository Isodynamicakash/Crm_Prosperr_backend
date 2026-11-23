package com.prosperr.crm.integrations.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The HTML version of a sequence email: lists, links and the unsubscribe footer. */
class GmailHtmlTest {
    @Test
    void introEmailRendersCleanly() {
        String text = """
                Hi Rita,

                Here's what it means for you as a finance leader:
                • Cost-neutral: no change to CTC
                • Fully compliant <both regimes> & more

                More details here: https://prosperr.io/flexi-benefit-plan

                Best,
                Asha Rao
                Prosperr.io

                --
                To stop these emails: https://crm.example.com/public/unsubscribe/abc""";
        String h = GmailMailGateway.html(text);
        assertTrue(h.contains("<ul"), h);
        assertEquals(2, h.split("<li").length - 1);
        assertTrue(h.contains("&lt;both regimes&gt; &amp; more"), "escaped");
        assertTrue(h.contains("<a href=\"https://prosperr.io/flexi-benefit-plan\""), h);
        assertTrue(h.contains("Best,<br>Asha Rao<br>Prosperr.io"), h);
        assertTrue(h.contains("border-top:1px solid") && h.contains("unsubscribe/abc\""), "footer with link");
        assertFalse(h.contains("--"), "separator not shown");
    }
}
