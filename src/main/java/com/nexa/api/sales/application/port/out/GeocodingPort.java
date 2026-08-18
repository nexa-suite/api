package com.nexa.api.sales.application.port.out;

import java.util.Optional;

/** Narrow boundary for converting a place/address into structured geography. */
public interface GeocodingPort {
    Optional<GeocodedPlace> geocode(String query);
}
