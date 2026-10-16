package com.nexa.api.inventoryavailability.domain.model.warehouse;

import java.util.Locale;

/** Structured physical address; coordinates remain a separate location value. */
public record WarehouseAddressDetails(String countryCode, String departmentCode, String provinceCode,
                                     String districtCode, String city, String roadType, String streetName,
                                     String streetNumber, String interior, String postalCode, String reference) {
    public WarehouseAddressDetails {
        countryCode = required(countryCode, 2).toUpperCase(Locale.ROOT);
        if (!countryCode.matches("[A-Z]{2}")) throw invalid();
        departmentCode = optional(departmentCode, 40);
        provinceCode = optional(provinceCode, 40);
        districtCode = optional(districtCode, 40);
        city = required(city, 160);
        roadType = required(roadType, 40);
        streetName = required(streetName, 200);
        streetNumber = required(streetNumber, 32);
        interior = optional(interior, 80);
        postalCode = optional(postalCode, 32);
        reference = optional(reference, 500);
        if ("PE".equals(countryCode) && (departmentCode == null || provinceCode == null || districtCode == null
                || !departmentCode.matches("[0-9]{2}") || !provinceCode.matches("[0-9]{4}")
                || !districtCode.matches("[0-9]{6}") || !provinceCode.startsWith(departmentCode)
                || !districtCode.startsWith(provinceCode))) throw invalid();
    }

    private static String required(String value, int max) {
        String normalized = optional(value, max);
        if (normalized == null) throw invalid();
        return normalized;
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) throw invalid();
        return normalized;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Warehouse address details are invalid");
    }
}
