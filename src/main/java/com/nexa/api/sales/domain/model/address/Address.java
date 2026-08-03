package com.nexa.api.sales.domain.model.address;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.util.Locale;

/** Immutable, tenant-owned delivery address value. Peru is the current supported geography. */
public record Address(String addressType, String line, String reference, String countryCode,
                      String departmentCode, String provinceCode, String districtCode) {
    public Address {
        addressType = optional(addressType, "STREET", 32);
        line = required(line, "Address line", 240);
        reference = optional(reference, null, 500);
        countryCode = required(countryCode, "Country code", 8).toUpperCase(Locale.ROOT);
        if (!"PE".equals(countryCode)) {
            throw new SalesInvariantViolation("Only Peru delivery addresses are supported");
        }
        departmentCode = required(departmentCode, "Department code", 40);
        provinceCode = required(provinceCode, "Province code", 40);
        districtCode = required(districtCode, "District code", 40);
    }

    public static Address peru(String addressType, String line, String reference,
                               String departmentCode, String provinceCode, String districtCode) {
        return new Address(addressType, line, reference, "PE", departmentCode, provinceCode, districtCode);
    }

    public String display() {
        return String.join(", ", java.util.stream.Stream.of(
                        addressType == null ? null : addressType + " " + line,
                        districtCode, provinceCode, departmentCode, "Peru")
                .filter(value -> value != null && !value.isBlank())
                .toList());
    }

    private static String required(String value, String label, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new SalesInvariantViolation(label + " is invalid");
        }
        return value.trim();
    }

    private static String optional(String value, String fallback, int max) {
        if (value == null || value.isBlank()) return fallback;
        if (value.trim().length() > max) throw new SalesInvariantViolation("Address field is too long");
        return value.trim();
    }
}
