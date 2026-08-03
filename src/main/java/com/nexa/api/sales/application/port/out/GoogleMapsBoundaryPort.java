package com.nexa.api.sales.application.port.out;

import java.util.Optional;

/** Anti-corruption boundary for Google Maps. HTTP/API-key concerns stay outside Sales. */
public interface GoogleMapsBoundaryPort {
    Optional<GoogleRoute> calculate(MapRoutingPort.MapRouteRequest request);

    record GoogleRoute(String reference, long distanceMeters, long durationSeconds, String previewUrl) { }
}
