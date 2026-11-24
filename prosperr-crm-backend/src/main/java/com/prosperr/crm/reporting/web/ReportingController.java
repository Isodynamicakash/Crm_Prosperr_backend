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
    private final com.prosperr.crm.reporting.application.BdrInsightsService insights;
    private final ReportService reports;
    private final TargetService targets;
    private final CurrentUser current;

    public ReportingController(DashboardService dashboards, com.prosperr.crm.reporting.application.BdrInsightsService insights,
                               ReportService reports, TargetService targets, CurrentUser current) {
        this.dashboards = dashboards; this.insights = insights; this.reports = reports; this.targets = targets; this.current = current;
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

    /** Per-BDR and per-AE L1 / L2 / L3 counts and targets for any period (managers and admins). */
    @GetMapping("/dashboards/scoreboard")
    @PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
    public Map<String, Object> scoreboard(@RequestParam(required = false) String preset,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.scoreboard(dashboards.range(preset, from, to));
    }

    /** A BDR's own L1-L3 numbers against prorated targets for any period, split by week. Managers may pass bdrId. */
    @GetMapping("/dashboards/bdr/scoreboard")
    public Map<String, Object> myScoreboard(@RequestParam(required = false) UUID bdrId, @RequestParam(required = false) String preset,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return dashboards.myScoreboard(self(bdrId, Role.BDR), dashboards.range(preset, from, to));
    }

    /** A BDR's own book of leads (managers pass bdrId to see anyone's). */
    @GetMapping("/dashboards/bdr/leads")
    public Map<String, Object> myLeads(@RequestParam(required = false) UUID bdrId, @RequestParam(required = false) String preset,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return insights.leads(self(bdrId, Role.BDR), dashboards.range(preset, from, to));
    }

    /** Companies by industry / city / size / source and how far they got; scope=team covers every company. */
    @GetMapping("/dashboards/bdr/leads/breakdown")
    public List<Map<String, Object>> leadBreakdown(@RequestParam(required = false) UUID bdrId, @RequestParam(defaultValue = "industry") String by,
                                                   @RequestParam(defaultValue = "mine") String scope) {
        UUID id = "team".equals(scope) && current.get().isLeadership() && bdrId == null ? current.get().userId() : self(bdrId, Role.BDR);
        return insights.breakdown(id, by, "team".equals(scope));
    }

    /** The BDR's own contacts: search, status, level, sort; touched=true keeps only people worked in the period. */
    @GetMapping("/dashboards/bdr/leads/list")
    public Map<String, Object> myLeadList(@RequestParam(required = false) UUID bdrId, @RequestParam(required = false) String q,
                                          @RequestParam(required = false) String status, @RequestParam(required = false) Integer level,
                                          @RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean touched,
                                          @RequestParam(required = false) String preset,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return insights.leadList(self(bdrId, Role.BDR), q, status, level, sort, touched ? dashboards.range(preset, from, to) : null, page, size);
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
