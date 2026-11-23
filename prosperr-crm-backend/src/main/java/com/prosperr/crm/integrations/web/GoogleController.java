package com.prosperr.crm.integrations.web;

import com.prosperr.crm.integrations.application.CalendarService;
import com.prosperr.crm.integrations.application.GoogleAccountService;
import com.prosperr.crm.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/integrations/google")
public class GoogleController {
    private final GoogleAccountService google;
    private final CalendarService calendar;
    private final CurrentUser current;

    public GoogleController(GoogleAccountService google, CalendarService calendar, CurrentUser current) {
        this.google = google; this.calendar = calendar; this.current = current;
    }

    @GetMapping
    public GoogleAccountService.Status status() { return google.status(current.get().userId()); }

    /** Returns Google's consent URL; the browser goes there and comes back through /callback. */
    @PostMapping("/connect")
    public Map<String, String> connect(@RequestBody(required = false) Map<String, String> body, HttpServletRequest req) {
        String back = body != null && body.get("returnTo") != null ? body.get("returnTo") : req.getHeader(HttpHeaders.ORIGIN);
        return Map.of("url", google.authUrl(current.get().userId(), back));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {
        var r = google.callback(code, state, error);
        String target = r.returnTo() + "/?google=" + (r.ok() ? "connected" : URLEncoder.encode(r.message(), StandardCharsets.UTF_8));
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @DeleteMapping
    public void disconnect() { google.disconnect(current.get().userId()); }

    /** Busy blocks on a day for the given team members (defaults to the caller). */
    @GetMapping("/busy")
    public List<CalendarService.Busy> busy(@RequestParam(required = false) List<UUID> userIds,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<UUID> ids = userIds == null || userIds.isEmpty() ? List.of(current.get().userId()) : userIds.stream().limit(5).toList();
        return calendar.busy(ids, date);
    }
}
