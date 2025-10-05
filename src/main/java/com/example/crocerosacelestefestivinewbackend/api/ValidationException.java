package com.example.crocerosacelestefestivinewbackend.api;

import java.util.List;
import java.util.Map;

public class ValidationException extends RuntimeException {
    private final List<Map<String, Object>> violations;

    public ValidationException(List<Map<String, Object>> violations) {
        super("Validation failed");
        this.violations = violations;
    }

    public List<Map<String, Object>> getViolations() {
        return violations;
    }
}


