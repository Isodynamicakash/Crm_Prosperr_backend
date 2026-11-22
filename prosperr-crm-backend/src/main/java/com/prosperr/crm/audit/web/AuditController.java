package com.prosperr.crm.audit.web;

import com.prosperr.crm.audit.application.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('MANAGER','ADMIN')")
public class AuditController {
    private final AuditService audit;
    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping("/{entityType}/{id}")
    public List<Map<String, Object>> history(@PathVariable String entityType, @PathVariable UUID id) {
        return audit.history(entityType, id);
    }
}
