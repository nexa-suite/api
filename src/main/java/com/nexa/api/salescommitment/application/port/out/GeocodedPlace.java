package com.nexa.api.salescommitment.application.port.out;

/** Structured result returned by geocoding boundaries. */
public record GeocodedPlace(String placeId, String label, MapCoordinate coordinate,
                            String addressLine, String departmentCode,
                            String provinceCode, String districtCode) {
}
