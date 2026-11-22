package com.prosperr.crm.reporting.web;

import com.prosperr.crm.reporting.application.DashboardService;
import com.prosperr.crm.reporting.application.ReportService;
import com.prosperr.crm.reporting.application.TargetService;
import com.prosperr.crm.shared.domain.PeriodType;
import com.prosperr.crm.shared.domain.Role;
import com.prosperr.crm.shared.jdbc.AccountFilter;
import com.prosperr.crm.shared.security.Actor;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.TableExport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ReportingController {
    private final DashboardService dashboards;
    private final ReportService reports;
    private final TargetService targets;
    private final CurrentUser current;

    public ReportingController(DashboardService dashboards, ReportService reports, TargetService targets, CurrentUser current) {
        this.dashboards = dashboards; this.reports = reports; this.targets = targets; this.current = current;
    }

    /** Own data for BDR / AE; leadership may pass a user id ("Performance reports: Own / Own / All"). */
    private UUID self(UUID requested, Role role) {
        Actor a = current.get();
        if (a.isLeadership()) {
            if (requested == null) throw new IllegalArgumentException("Pass the user id");
            return requested;
        }
        if (a.role() != role) throw new ForbiddenException("This dashboard is for " + role);
        return a.userId();
    }

    /** Live pulse for every role: own work for BDR / AE, the whole team for managers and admins. */
    @GetMapping("/dashboards/pulse")
    public Map<String, Object> pulse(@RequestParam(required = false) String preset,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Actor a = current.get();
        return dashboards.pulse(a.isLeadership() ? null : a.userId(), a.role() == Role.AE, dashboards.range(preset, from, to));
    }

    @GetMapping("/dashboards/bdr")
    public Map<String, Object> bdr(@RequestParam(required = false) UUID bdrId, @RequestParam(required = false) String preset,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.bdr(self(bdrId, Role.BDR), dashboards.range(preset, from, to));
    }

    @GetMapping("/dashboards/ae")
    public Map<String, Object> ae(@RequestParam(required = false) UUID aeId, @RequestParam(required = false) String preset,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.ae(self(aeId, Role.AE), dashboards.range(preset, from, to));
    }

    @GetMapping("/dashboards/leadership")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, Object> leadership(@RequestParam(required = false) String preset,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) String status, @RequestParam(required = false) List<String> stage,
                                          @RequestParam(required = false) UUID aeId, @RequestParam(required = false) UUID bdrId,
                                          @RequestParam(required = false) UUID cityId, @RequestParam(required = false) String sizeBucket,
                                          @RequestParam(required = false) UUID industryId, @RequestParam(required = false) String temperature) {
        AccountFilter f = new AccountFilter(null, status == null ? "ALL" : status, stage, null, aeId, bdrId, cityId, sizeBucket,
                industryId, temperature, null, null, null);
        return dashboards.leadership(f, current.get(), dashboards.range(preset, from, to));
    }

    @GetMapping("/dashboards/hygiene")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, Object> hygiene() { return dashboards.hygiene(); }

    @GetMapping("/dashboards/drilldown")
    public ResponseEntity<?> drilldown(@RequestParam String metric, @RequestParam(required = false) String status,
                                       @RequestParam(required = false) UUID aeId, @RequestParam(required = false) UUID bdrId,
                                       @RequestParam(required = false) UUID cityId, @RequestParam(required = false) String sizeBucket,
                                       @RequestParam(required = false) String format) {
        AccountFilter f = new AccountFilter(null, status == null ? "ALL" : status, null, null, aeId, bdrId, cityId, sizeBucket,
                null, null, null, null, null);
        return TableExport.respond(dashboards.drilldown(metric, f, current.get()), format, "drilldown-" + metric);
    }

    // ---- reports (P1) ----
    @GetMapping("/reports/l1-not-progressed")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public ResponseEntity<?> l1(@RequestParam(required = false) UUID aeId, @RequestParam(required = false) UUID bdrId,
                                @RequestParam(required = false) String sizeBucket, @RequestParam(required = false) String status,
                                @RequestParam(required = false) String format) {
        AccountFilter f = new AccountFilter(null, status == null ? "ALL" : status, null, null, aeId, bdrId, null, sizeBucket,
                null, null, null, null, null);
        return TableExport.respond(reports.l1NotProgressed(f, current.get()), format, "l1-not-progressed");
    }

    @GetMapping("/reports/stakeholder-coverage")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, List<Map<String, Object>>> coverage() {
        return reports.stakeholderCoverage(new AccountFilter(null, "ACTIVE", null, "PIPELINE", null, null, null, null, null, null,
                null, null, null), current.get());
    }

    @GetMapping("/reports/inactive-reasons")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, List<Map<String, Object>>> inactive(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.inactiveReasons(from, to);
    }

    @GetMapping("/reports/workload")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, List<Map<String, Object>>> workload(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.workload(from, to);
    }

    @GetMapping("/reports/ageing")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, List<Map<String, Object>>> ageing(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.ageing(from, to);
    }

    @GetMapping("/reports/sql-quality")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, List<Map<String, Object>>> sqlQuality(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.sqlQuality(from, to);
    }

    // ---- targets (M9) ----
    @PutMapping("/targets")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public void setTarget(@RequestBody TargetService.SetTarget t) { targets.set(t, current.get().userId()); }

    @GetMapping("/targets/achievement")
    public List<TargetService.Achievement> achievement(@RequestParam(required = false) UUID userId,
                                                       @RequestParam(defaultValue = "MONTH") PeriodType period,
                                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Actor a = current.get();
        UUID who = a.isLeadership() ? userId : a.userId();
        return targets.achievement(who, period, date == null ? LocalDate.now() : date);
    }
}
