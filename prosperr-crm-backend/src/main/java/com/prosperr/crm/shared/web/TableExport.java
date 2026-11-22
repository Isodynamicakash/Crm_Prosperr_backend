package com.prosperr.crm.shared.web;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** PRD M10-3: export any list or report to CSV or XLSX. */
public final class TableExport {
    private TableExport() {}

    public static ResponseEntity<?> respond(List<Map<String, Object>> rows, String format, String baseName) {
        if (format == null || format.isBlank() || format.equalsIgnoreCase("json")) return ResponseEntity.ok(rows);
        try {
            if (format.equalsIgnoreCase("csv")) {
                return file(csv(rows), baseName + ".csv", "text/csv");
            }
            if (format.equalsIgnoreCase("xlsx")) {
                return file(xlsx(rows), baseName + ".xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Export failed", e);
        }
        throw new IllegalArgumentException("format must be json, csv or xlsx");
    }

    static List<String> headers(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet());
    }

    public static byte[] csv(List<Map<String, Object>> rows) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> h = headers(rows);
        try (CSVPrinter p = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8),
                CSVFormat.DEFAULT.builder().setHeader(h.toArray(String[]::new)).build())) {
            for (Map<String, Object> r : rows) {
                List<Object> vals = new ArrayList<>();
                for (String k : h) vals.add(r.get(k));
                p.printRecord(vals);
            }
        }
        return out.toByteArray();
    }

    public static byte[] xlsx(List<Map<String, Object>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("export");
            List<String> h = headers(rows);
            Row hr = s.createRow(0);
            for (int i = 0; i < h.size(); i++) hr.createCell(i).setCellValue(h.get(i));
            int r = 1;
            for (Map<String, Object> row : rows) {
                Row x = s.createRow(r++);
                for (int i = 0; i < h.size(); i++) {
                    Object v = row.get(h.get(i));
                    if (v instanceof Number n) x.createCell(i).setCellValue(n.doubleValue());
                    else x.createCell(i).setCellValue(v == null ? "" : v.toString());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static ResponseEntity<byte[]> file(byte[] body, String name, String type) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(MediaType.parseMediaType(type))
                .body(body);
    }
}
