package com.nexa.api.sales.domain.model.reference;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

public record PeruGeographyOption(long id, PeruGeographyLevel level, String code, String label,
                                  String parentCode, boolean active) {
    public PeruGeographyOption {
        if (id < 0) throw new SalesInvariantViolation("Reference id cannot be negative");
        if (level == null) throw new SalesInvariantViolation("Reference level is required");
        code = required(code, "Reference code", 40);
        label = required(label, "Reference label", 120);
        parentCode = parentCode == null || parentCode.isBlank() ? null : parentCode.trim();
        if (level == PeruGeographyLevel.DEPARTMENT && parentCode != null) {
            throw new SalesInvariantViolation("Department cannot have a parent geography");
        }
        if (level != PeruGeographyLevel.DEPARTMENT && (parentCode == null || parentCode.isBlank())) {
            throw new SalesInvariantViolation("Child geography requires a parent");
        }
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }
}
