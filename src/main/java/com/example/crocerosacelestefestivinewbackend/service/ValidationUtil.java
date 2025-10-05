package com.example.crocerosacelestefestivinewbackend.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ValidationUtil {
    static void addV(List<Map<String, Object>> violations, int row, String field, String message) {
        Map<String, Object> m = new HashMap<>();
        m.put("row", row);
        m.put("field", field);
        m.put("message", message);
        violations.add(m);
    }
}


