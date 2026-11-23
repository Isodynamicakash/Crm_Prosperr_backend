package com.prosperr.crm.importer.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Strategy: one reader per file type. Rows come back as header -> cell text, in file order. */
public interface RowReader {
    boolean supports(String fileName);

    /** Rows of the given sheet (null = first sheet; CSV has only one). */
    List<Map<String, String>> read(InputStream in, String sheet) throws IOException;

    default List<Map<String, String>> read(InputStream in) throws IOException { return read(in, null); }

    /** Sheet names in file order (CSV: a single unnamed sheet). */
    default List<String> sheets(InputStream in) throws IOException { return List.of(); }

    /** Header names made unique and non-blank: blank -> "Column C", repeats -> "Name (2)". */
    static List<String> cleanHeaders(List<String> raw) {
        List<String> out = new java.util.ArrayList<>();
        Map<String, Integer> seen = new java.util.HashMap<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null ? "" : raw.get(i).replace("﻿", "").trim();
            if (h.isEmpty()) h = "Column " + column(i);
            int n = seen.merge(h.toLowerCase(), 1, Integer::sum);
            out.add(n == 1 ? h : h + " (" + n + ")");
        }
        return out;
    }

    static String column(int i) {
        StringBuilder sb = new StringBuilder();
        for (int n = i + 1; n > 0; n = (n - 1) / 26) sb.insert(0, (char) ('A' + (n - 1) % 26));
        return sb.toString();
    }
}
