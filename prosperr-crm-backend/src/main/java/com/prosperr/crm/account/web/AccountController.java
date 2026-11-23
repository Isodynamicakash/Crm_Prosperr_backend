package com.prosperr.crm.account.web;

import com.prosperr.crm.account.api.AccountApi;
import com.prosperr.crm.account.api.AccountView;
import com.prosperr.crm.account.application.AccountQueryService;
import com.prosperr.crm.account.application.AccountService;
import com.prosperr.crm.account.application.SavedViewService;
import com.prosperr.crm.shared.jdbc.AccountFilter;
import com.prosperr.crm.shared.security.Action;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.ForbiddenException;
import com.prosperr.crm.shared.web.TableExport;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final AccountService accounts;
    private final AccountQueryService queries;
    private final SavedViewService views;
    private final CurrentUser current;

    public AccountController(AccountService accounts, AccountQueryService queries, SavedViewService views, CurrentUser current) {
        this.accounts = accounts; this.queries = queries; this.views = views; this.current = current;
    }

    /** M1-1 + duplicate rules: creates the account or joins the existing one (M2-9). */
    @PostMapping("/accounts")
    public AccountApi.Resolution create(@RequestBody AccountApi.CreateAccount req) {
        var actor = current.get();
        if (!new com.prosperr.crm.shared.security.AccessPolicy().can(actor, Action.WORK_PROSPECT)) {
            throw new ForbiddenException("Only BDRs and leadership create prospects");
        }
        return accounts.resolveOrCreate(req, actor);
    }

    /** List view: default Active; status=INACTIVE or ALL (M8-8). format=csv|xlsx exports (M10-3). */
    @GetMapping("/accounts")
    public ResponseEntity<?> list(@RequestParam(required = false) String q,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) List<String> stage,
                                  @RequestParam(required = false) String zone,
                                  @RequestParam(required = false) UUID aeId,
                                  @RequestParam(required = false) UUID bdrId,
                                  @RequestParam(required = false) Boolean workedByMe,
                                  @RequestParam(required = false) UUID cityId,
                                  @RequestParam(required = false) String sizeBucket,
                                  @RequestParam(required = false) UUID industryId,
                                  @RequestParam(required = false) String temperature,
                                  @RequestParam(required = false) Boolean flagged,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "50") int size,
                                  @RequestParam(required = false) String sort,
                                  @RequestParam(required = false) String format) {
        var actor = current.get();
        if (actor.role() == com.prosperr.crm.shared.domain.Role.AE && aeId != null && !aeId.equals(actor.userId())) {
            throw new ForbiddenException("An AE can list only their own leads");
        }
        AccountFilter f = new AccountFilter(q, status, stage, zone, aeId, bdrId, cityId, sizeBucket, industryId, temperature,
                flagged, from, to, Boolean.TRUE.equals(workedByMe) ? actor.userId() : null);
        if (format != null && !format.equalsIgnoreCase("json")) {
            return TableExport.respond(queries.list(f, current.get(), 0, 100_000, sort).items(), format, "accounts");
        }
        return ResponseEntity.ok(queries.list(f, current.get(), page, Math.min(size, 500), sort));
    }

    @GetMapping("/accounts/{id}")
    public Map<String, Object> page(@PathVariable UUID id) { return queries.page(id, current.get()); }

    @PatchMapping("/accounts/{id}")
    public AccountView update(@PathVariable UUID id, @RequestBody AccountService.AccountUpdate req) {
        return accounts.update(id, req, current.get());
    }

    @GetMapping("/accounts/{id}/multithreading")
    public Map<String, Object> multithreading(@PathVariable UUID id) { return queries.multithreading(id, current.get()); }

    @GetMapping("/accounts/{id}/timeline")
    public AccountQueryService.Timeline timeline(@PathVariable UUID id,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @RequestParam(required = false) List<String> type,
                                                 @RequestParam(required = false) UUID ownerId) {
        return queries.timeline(id, current.get(), from, to, type, ownerId);
    }

    @GetMapping("/search")
    public Map<String, List<Map<String, Object>>> search(@RequestParam String q) { return queries.search(q, current.get()); }

    // ---- saved views (M1-6) ----
    public record SaveView(String name, String entity, String filters, boolean shared) {}

    @GetMapping("/saved-views")
    public List<Map<String, Object>> views(@RequestParam(defaultValue = "ACCOUNT") String entity) {
        return views.list(current.get().userId(), entity);
    }

    @PostMapping("/saved-views")
    public Map<String, UUID> save(@RequestBody SaveView v) {
        return Map.of("id", views.save(current.get().userId(), v.name(), v.entity(), v.filters(), v.shared()));
    }

    @DeleteMapping("/saved-views/{id}")
    public void deleteView(@PathVariable UUID id) { views.delete(current.get().userId(), id); }
}
