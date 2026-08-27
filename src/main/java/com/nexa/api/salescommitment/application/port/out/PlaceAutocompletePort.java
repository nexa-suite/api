package com.nexa.api.salescommitment.application.port.out;

import java.util.List;

/** Narrow boundary for provider-backed place suggestions. */
public interface PlaceAutocompletePort {
    List<PlaceSuggestion> search(String query);

    record PlaceSuggestion(String placeId, String label, MapCoordinate coordinate) {
    }
}
