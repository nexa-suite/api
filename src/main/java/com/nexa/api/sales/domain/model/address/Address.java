package com.nexa.api.sales.domain.model.address;

import com.nexa.api.sales.domain.exception.SalesInvariantViolation;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Immutable, tenant-owned delivery address value. Peru is the current supported geography. */
public record Address(String addressType, String line, String reference, String countryCode,
                      String departmentCode, String provinceCode, String districtCode,
                      String recipientName, String recipientPhone, String roadType, String streetName,
                      String streetNumber, String interior, String postalCode,
                      String receivingInstructions, String receivingHours,
                      BigDecimal latitude, BigDecimal longitude, String placeId, String source) {
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
        recipientName = optional(recipientName, null, 160);
        recipientPhone = optional(recipientPhone, null, 48);
        roadType = optional(roadType, addressType, 32);
        streetName = optional(streetName, null, 180);
        streetNumber = optional(streetNumber, null, 32);
        interior = optional(interior, null, 64);
        postalCode = optional(postalCode, null, 32);
        receivingInstructions = optional(receivingInstructions, null, 1000);
        receivingHours = optional(receivingHours, null, 240);
        if ((latitude == null) != (longitude == null)) throw new SalesInvariantViolation("Coordinates must be supplied together");
        if (latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) {
            throw new SalesInvariantViolation("Coordinates are invalid");
        }
        placeId = optional(placeId, null, 240);
        source = optional(source, "MANUAL", 24).toUpperCase(Locale.ROOT);
        if (!Set.of("MANUAL", "SAVED", "CURRENT_LOCATION", "MAP_PIN", "PLACES").contains(source)) {
            throw new SalesInvariantViolation("Address source is invalid");
        }
    }

    public Address(String addressType, String line, String reference, String countryCode,
                   String departmentCode, String provinceCode, String districtCode) {
        this(addressType, line, reference, countryCode, departmentCode, provinceCode, districtCode,
                null, null, addressType, null, null, null, null, null, null, null, null, null, "MANUAL");
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
