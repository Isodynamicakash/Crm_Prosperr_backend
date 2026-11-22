package com.prosperr.crm.importer.application;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

@Component
class XlsxRowReader implements RowReader {
    public boolean supports(String fileName) { return fileName != null && fileName.toLowerCase().endsWith(".xlsx"); }

    public List<Map<String, String>> read(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Iterator<Row> it = sheet.iterator();
            if (!it.hasNext()) return rows;
            Row header = it.next();
            List<String> headers = new ArrayList<>();
            header.forEach(c -> headers.add(fmt.formatCellValue(c).trim()));
            while (it.hasNext()) {
                Row r = it.next();
                Map<String, String> row = new LinkedHashMap<>();
                boolean any = false;
                for (int i = 0; i < headers.size(); i++) {
                    Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    String v = c == null ? null : fmt.formatCellValue(c).trim();
                    if (v != null && !v.isEmpty()) any = true;
                    row.put(headers.get(i), v);
                }
                if (any) rows.add(row);
            }
        }
        return rows;
    }
}
