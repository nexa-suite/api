package com.nexa.api.salescommitment.application.port.out;

import java.util.Optional;
import java.util.List;

/** Anti-corruption boundary for Google Maps. HTTP/API-key concerns stay outside Sales. */
public interface GoogleMapsBoundaryPort {
    Optional<GoogleRoute> calculate(MapRoutingPort.MapRouteRequest request);

    default List<PlaceAutocompletePort.PlaceSuggestion> autocomplete(String query) {
        return List.of();
    }

    default Optional<GeocodedPlace> geocode(String query) {
        return Optional.empty();
    }

    default Optional<GeocodedPlace> reverseGeocode(MapCoordinate coordinate) {
        return Optional.empty();
    }

    default Optional<DistanceMatrixPort.DistanceEstimate> distance(MapCoordinate origin, MapCoordinate destination) {
        return Optional.empty();
    }

    record GoogleRoute(String reference, long distanceMeters, long durationSeconds, String previewUrl) { }
}
