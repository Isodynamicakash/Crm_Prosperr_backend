package com.prosperr.crm.importer.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prosperr.crm.importer.application.ImportService;
import com.prosperr.crm.shared.security.CurrentUser;
import com.prosperr.crm.shared.web.TableExport;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/imports")
public class ImportController {
    private final ImportService imports;
    private final CurrentUser current;
    private final ObjectMapper json;

    public ImportController(ImportService imports, CurrentUser current, ObjectMapper json) {
        this.imports = imports; this.current = current; this.json = json;
    }

    /** mapping: JSON object of file column -> field, e.g. {"Company":"company","Name":"fullName","Mobile":"phone"}. */
    @PostMapping(consumes = "multipart/form-data")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file, @RequestParam("mapping") String mapping,
                                      @RequestParam(value = "source", required = false) String source) throws IOException {
        Map<String, String> m = json.readValue(mapping, new TypeReference<Map<String, String>>() {});
        return imports.upload(file.getOriginalFilename(), file.getInputStream(), source, m, current.get());
    }

    @GetMapping("/fields")
    public Object fields() { return ImportService.FIELDS; }

    @GetMapping("/{id}")
    public Map<String, Object> summary(@PathVariable UUID id) { return imports.summary(id); }

    @GetMapping("/{id}/rows")
    public ResponseEntity<?> rows(@PathVariable UUID id, @RequestParam(required = false) String format) {
        return TableExport.respond(imports.rows(id), format, "import-report");
    }

    @PostMapping("/{id}/commit")
    public Map<String, Object> commit(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeReview) {
        return imports.commit(id, includeReview, current.get());
    }
}
