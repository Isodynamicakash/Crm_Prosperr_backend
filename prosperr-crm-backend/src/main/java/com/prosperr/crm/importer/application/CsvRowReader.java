package com.prosperr.crm.importer.application;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
class CsvRowReader implements RowReader {
    public boolean supports(String fileName) { return fileName != null && fileName.toLowerCase().endsWith(".csv"); }

    public List<Map<String, String>> read(InputStream in) throws IOException {
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
                .setTrim(true).build();
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVParser p = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), fmt)) {
            List<String> headers = p.getHeaderNames();
            for (CSVRecord r : p) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String h : headers) row.put(h.replace("\uFEFF", "").trim(), r.isMapped(h) ? r.get(h) : null);   // Excel adds a BOM
                rows.add(row);
            }
        }
        return rows;
    }
}
