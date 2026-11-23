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

/** CSV (UTF-8, Excel's BOM tolerated). The first non-empty line is the header; columns are read by position. */
@Component
class CsvRowReader implements RowReader {
    public boolean supports(String fileName) { return fileName != null && fileName.toLowerCase().endsWith(".csv"); }

    public List<Map<String, String>> read(InputStream in, String sheet) throws IOException {
        CSVFormat fmt = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).build();
        List<Map<String, String>> rows = new ArrayList<>();
        try (CSVParser p = CSVParser.parse(new InputStreamReader(in, StandardCharsets.UTF_8), fmt)) {
            List<String> headers = null;
            for (CSVRecord r : p) {
                if (headers == null) {
                    List<String> raw = new ArrayList<>();
                    r.forEach(raw::add);
                    headers = RowReader.cleanHeaders(raw);         // strips the BOM, names blank columns, numbers repeats
                    continue;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i < r.size() ? r.get(i) : null);
                rows.add(row);
            }
        }
        return rows;
    }
}
