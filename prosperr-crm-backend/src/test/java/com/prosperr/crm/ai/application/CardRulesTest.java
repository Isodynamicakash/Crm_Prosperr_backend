package com.prosperr.crm.ai.application;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The rules that raise card accuracy beyond the AI read. */
class CardRulesTest {

    @Test
    void aVcardQrInAPhotoIsReadExactly() throws Exception {
        String vcard = "BEGIN:VCARD\nVERSION:3.0\nN:Kapoor;Neha\nFN:Neha Kapoor\nORG:Acme Foods Pvt Ltd\nTITLE:Head - HR\n"
                + "TEL;TYPE=CELL:+91 98765 43210\nEMAIL;TYPE=WORK:Neha.Kapoor@acmefoods.in\nURL:https://acmefoods.in\n"
                + "ADR;TYPE=WORK:;;12 MG Road;Bengaluru;KA;560001;India\nEND:VCARD";
        BufferedImage qr = MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(vcard, BarcodeFormat.QR_CODE, 300, 300));
        BufferedImage photo = new BufferedImage(1200, 700, BufferedImage.TYPE_INT_RGB);       // the QR sits in the corner of a card
        Graphics2D g = photo.createGraphics();
        g.setColor(new Color(235, 230, 220)); g.fillRect(0, 0, 1200, 700);
        g.setColor(Color.DARK_GRAY); g.setFont(new Font("SansSerif", Font.BOLD, 40)); g.drawString("Neha Kapoor", 80, 200);
        g.drawImage(qr, 820, 300, null); g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(photo, "png", png);

        List<String> codes = CardRules.qrCodes(png.toByteArray());
        assertEquals(1, codes.size());
        Map<String, Object> f = CardRules.parseQr(codes.get(0));
        assertEquals("Neha Kapoor", f.get("fullName"));
        assertEquals("Acme Foods Pvt Ltd", f.get("company"));
        assertEquals("Head - HR", f.get("designation"));
        assertEquals(List.of("neha.kapoor@acmefoods.in"), f.get("emails"));
        assertEquals(List.of("+91 98765 43210"), f.get("phones"));
        assertEquals("https://acmefoods.in", f.get("website"));
        assertEquals("Bengaluru", f.get("city"));
    }

    @Test
    void otherQrKinds() {
        Map<String, Object> m = CardRules.parseQr("MECARD:N:Iyer,Ravi;TEL:+919812345678;EMAIL:ravi@globex.com;ORG:Globex;;");
        assertEquals("Ravi Iyer", m.get("fullName"));
        assertEquals(List.of("ravi@globex.com"), m.get("emails"));
        assertEquals("https://www.linkedin.com/in/ravi-iyer", CardRules.parseQr("https://www.linkedin.com/in/ravi-iyer").get("linkedin"));
        assertEquals("https://globex.com", CardRules.parseQr("https://globex.com").get("website"));
        assertTrue(CardRules.parseQr("just some text").isEmpty());
        assertTrue(CardRules.qrCodes(new byte[2000]).isEmpty(), "not an image: no crash, no QR");
    }

    @Test
    void lettersReadAsDigitsInPhonesAreFixed() {
        assertEquals("+91 98100 45678", CardRules.repairPhone("+91 98I00 4S678"));
        assertEquals("+91 90040 87654", CardRules.repairPhone("+91 9OO4O 87654"));
        assertEquals("Ext. 204", CardRules.repairPhone("Ext. 204"), "words are left alone");
        assertTrue(CardRules.plausibleIndianPhone("+91 98765 43210"));
        assertTrue(CardRules.plausibleIndianPhone("080 4123 4567"));
        assertTrue(CardRules.plausibleIndianPhone("+1 415 555 0100"));
        assertFalse(CardRules.plausibleIndianPhone("+91 18765 43210"), "Indian mobiles start 6-9");
    }

    @Test
    void emailsAreCleanedAndFreeMailTyposFixed() {
        assertEquals("neha.k@acme.com", CardRules.repairEmail("Neha.K (at) acme ,com"));
        assertEquals("neha@acme.com", CardRules.repairEmail("mailto:neha@acme.com."));
        assertEquals("rahul@gmail.com", CardRules.repairEmail("rahul@gmai1.com"));
        assertNull(CardRules.repairEmail("www.acme.com"));
    }

    @Test
    void emailDomainAndWebsiteMustAgree() {
        assertEquals("polarisinfra.com", CardRules.agreeDomain("polarisinfre.com", "polarisinfra.com", "Polaris Infra"));
        assertEquals("polarisinfra.com", CardRules.agreeDomain("polarisinfra.com", "po1arisinfra.com", "Polaris Infra"));
        assertNull(CardRules.agreeDomain("acme.com", "globex.com", "Acme"), "different companies: leave alone");
        assertNull(CardRules.agreeDomain("gmail.com", "gmail.co", "X"));
    }

    @Test
    void theEmailCorrectsAClassicOcrMisreadOfTheName() {
        CardRules.NameCheck c = CardRules.checkName("Rahul Sharrna", List.of("rahul.sharma@acme.com"));
        assertEquals("Rahul Sharma", c.name());
        assertTrue(c.fixed());
        CardRules.NameCheck odd = CardRules.checkName("Priya Menen", List.of("priya.menon@acme.com"));
        assertEquals("Priya Menen", odd.name(), "not a known confusion: flagged, not changed");
        assertEquals("Menen / menon", odd.mismatch());
        assertNull(CardRules.checkName("Gaurav Bansal", List.of("gaurav.bansal@x.com")).mismatch());
        assertNull(CardRules.checkName("Farah Siddiqui", List.of("farah.s@meridianhc.in")).mismatch());
    }

    @Test
    void twoReadsAgreeIgnoringFormatting() {
        assertTrue(CardScanService.same("Rahul  Sharma", "rahul sharma"));
        assertTrue(CardScanService.same(List.of("+91 98765 43210"), List.of("+919876543210")));
        assertFalse(CardScanService.same("Rahul Sharma", "Rahul Verma"));
    }
}
