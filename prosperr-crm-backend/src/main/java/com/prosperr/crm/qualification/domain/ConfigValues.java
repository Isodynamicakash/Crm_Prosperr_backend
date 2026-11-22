package com.prosperr.crm.qualification.domain;

import java.util.List;
import java.util.Map;

final class ConfigValues {
    private ConfigValues() {}

    static int intValue(Map<String, Object> cfg, String key, int def) {
        Object v = cfg == null ? null : cfg.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    static List<Integer> ints(Map<String, Object> cfg, String key, List<Integer> def) {
        Object v = cfg == null ? null : cfg.get(key);
        if (v instanceof List<?> l) return l.stream().map(o -> ((Number) o).intValue()).toList();
        return def;
    }
}
