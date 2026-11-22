package com.prosperr.crm.importer.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Strategy: one reader per file type. Rows come back as header -> cell text, in file order. */
public interface RowReader {
    boolean supports(String fileName);
    List<Map<String, String>> read(InputStream in) throws IOException;
}
