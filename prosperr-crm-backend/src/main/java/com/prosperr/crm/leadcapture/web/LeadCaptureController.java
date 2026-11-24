package com.prosperr.crm.leadcapture.web;

import com.prosperr.crm.leadcapture.application.LeadCaptureService;
import com.prosperr.crm.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class LeadCaptureController {
    private final LeadCaptureService service;
    private final CurrentUser current;

    public LeadCaptureController(LeadCaptureService service, CurrentUser current) { this.service = service; this.current = current; }

    // ------------------------------------------------------------------ signed in: the BDR's own QR codes

    record NewQr(String eventName, boolean sendWelcome) {}
    record QrPatch(Boolean active, Boolean sendWelcome) {}

    @GetMapping("/api/lead-qr")
    @PreAuthorize("hasAnyRole('BDR','MANAGER','ADMIN')")
    public List<LeadCaptureService.Qr> list() { return service.list(current.get()); }

    @PostMapping("/api/lead-qr")
    @PreAuthorize("hasAnyRole('BDR','MANAGER','ADMIN')")
    public Map<String, UUID> create(@RequestBody NewQr body) {
        return Map.of("id", service.create(body.eventName(), body.sendWelcome(), current.get()));
    }

    @PatchMapping("/api/lead-qr/{id}")
    @PreAuthorize("hasAnyRole('BDR','MANAGER','ADMIN')")
    public ResponseEntity<Void> update(@PathVariable UUID id, @RequestBody QrPatch body) {
        service.update(id, body.active(), body.sendWelcome(), current.get());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/lead-qr/{id}/captures")
    @PreAuthorize("hasAnyRole('BDR','MANAGER','ADMIN')")
    public List<Map<String, Object>> captures(@PathVariable UUID id) { return service.captures(id, current.get()); }

    // ------------------------------------------------------------------ public: the prospect's phone

    @GetMapping("/public/qr/{token}")
    public Map<String, Object> describe(@PathVariable String token) { return service.describe(token); }

    @PostMapping("/public/qr/{token}")
    public Map<String, Boolean> submit(@PathVariable String token, @RequestBody LeadCaptureService.Submission body, HttpServletRequest req) {
        service.submit(token, body, clientIp(req));
        return Map.of("ok", true);
    }

    /** Render and Vercel sit in front of the app: the first X-Forwarded-For entry is the visitor. */
    static String clientIp(HttpServletRequest req) {
        String f = req.getHeader("X-Forwarded-For");
        return f != null && !f.isBlank() ? f.split(",")[0].trim() : req.getRemoteAddr();
    }
}
