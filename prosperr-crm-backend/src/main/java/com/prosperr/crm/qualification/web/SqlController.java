package com.prosperr.crm.qualification.web;

import com.prosperr.crm.qualification.application.SqlService;
import com.prosperr.crm.shared.security.CurrentUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class SqlController {
    private final SqlService sql;
    private final CurrentUser current;

    public SqlController(SqlService sql, CurrentUser current) { this.sql = sql; this.current = current; }

    /** Live checklist while the BDR fills the form (POST so the draft BANT can be sent). */
    @PostMapping("/accounts/{id}/sql/checklist")
    public SqlService.Checklist checklist(@PathVariable UUID id, @RequestBody SqlService.SubmitSql draft) {
        return sql.checklist(id, draft, current.get());
    }

    @PostMapping("/accounts/{id}/sql")
    public Map<String, Object> submit(@PathVariable UUID id, @RequestBody SqlService.SubmitSql req) {
        return sql.submit(id, req, current.get());
    }

    @GetMapping("/sql")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status, @RequestParam(required = false) UUID bdrId) {
        return sql.list(status, bdrId, current.get());
    }

    @GetMapping("/sql/{id}")
    public Map<String, Object> get(@PathVariable UUID id) { return sql.get(id, current.get()); }

    @PostMapping("/sql/{id}/validate")
    public Map<String, Object> validate(@PathVariable UUID id, @RequestBody SqlService.Validate v) {
        return sql.validate(id, v, current.get());
    }

    @PostMapping("/sql/{id}/corrections")
    public List<Map<String, Object>> correct(@PathVariable UUID id, @RequestBody SqlService.Correction c) {
        return sql.correct(id, c, current.get());
    }
}
