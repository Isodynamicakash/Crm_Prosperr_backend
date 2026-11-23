package com.prosperr.crm.importer.application;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

/**
 * Excel (.xlsx). Picks the named sheet (default the first) and finds the header row: the first of the top 15 rows
 * with at least two filled cells, so title rows or a blank first line above the table don't break the import.
 */
@Component
class XlsxRowReader implements RowReader {
    public boolean supports(String fileName) { return fileName != null && fileName.toLowerCase().endsWith(".xlsx"); }

    @Override
    public List<String> sheets(InputStream in) throws IOException {
        List<String> names = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(in)) {
            for (int i = 0; i < wb.getNumberOfSheets(); i++) if (!wb.isSheetHidden(i)) names.add(wb.getSheetName(i));
        }
        return names;
    }

    public List<Map<String, String>> read(InputStream in, String sheetName) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = sheetName == null ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) throw new IllegalArgumentException("No sheet named " + sheetName);
            List<Row> all = new ArrayList<>();
            sheet.forEach(all::add);
            int h = 0;
            for (int i = 0; i < Math.min(15, all.size()); i++) {
                if (filled(all.get(i), fmt) >= 2) { h = i; break; }
            }
            if (all.isEmpty()) return rows;
            Row header = all.get(h);
            int width = Math.max(header.getLastCellNum(), 0);
            for (Row r : all) width = Math.max(width, r.getLastCellNum());
            List<String> raw = new ArrayList<>();
            for (int i = 0; i < width; i++) {
                Cell c = header.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                raw.add(c == null ? "" : fmt.formatCellValue(c));
            }
            while (!raw.isEmpty() && raw.get(raw.size() - 1).isBlank() && emptyColumn(all, h, raw.size() - 1, fmt)) raw.remove(raw.size() - 1);
            List<String> headers = RowReader.cleanHeaders(raw);
            for (int ri = h + 1; ri < all.size(); ri++) {
                Row r = all.get(ri);
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

    private static int filled(Row r, DataFormatter fmt) {
        int n = 0;
        for (Cell c : r) if (!fmt.formatCellValue(c).isBlank()) n++;
        return n;
    }

    private static boolean emptyColumn(List<Row> rows, int from, int col, DataFormatter fmt) {
        for (int i = from + 1; i < rows.size(); i++) {
            Cell c = rows.get(i).getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            if (c != null && !fmt.formatCellValue(c).isBlank()) return false;
        }
        return true;
    }
}
