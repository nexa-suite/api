package com.nexa.api.salescommitment.application.port.out;

import java.util.Optional;

/** Narrow boundary for converting a confirmed pin into structured geography. */
public interface ReverseGeocodingPort {
    Optional<GeocodedPlace> reverseGeocode(MapCoordinate coordinate);
}
