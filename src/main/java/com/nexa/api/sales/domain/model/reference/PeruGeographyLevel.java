package com.nexa.api.sales.domain.model.reference;

public enum PeruGeographyLevel {
    DEPARTMENT,
    PROVINCE,
    DISTRICT,
    ROAD_TYPE;

    public static PeruGeographyLevel fromResource(String resource) {
        if (resource == null) throw new IllegalArgumentException("Reference resource is required");
        return switch (resource.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "departments" -> DEPARTMENT;
            case "provinces" -> PROVINCE;
            case "districts" -> DISTRICT;
            case "road-types", "roadtypes" -> ROAD_TYPE;
            default -> throw new IllegalArgumentException("Unsupported Peru geography resource");
        };
    }
}
