package com.nexa.api.sales.infrastructure.maps;

import com.nexa.api.sales.application.port.out.GoogleMapsBoundaryPort;
import com.nexa.api.sales.application.port.out.DistanceMatrixPort;
import com.nexa.api.sales.application.port.out.GeocodedPlace;
import com.nexa.api.sales.application.port.out.GeocodingPort;
import com.nexa.api.sales.application.port.out.MapCoordinate;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.application.port.out.PlaceAutocompletePort;
import com.nexa.api.sales.application.port.out.ReverseGeocodingPort;
import com.nexa.api.sales.application.port.out.RoutePreviewPort;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Google Maps adapter. Credentials, HTTP and SDK details stay behind GoogleMapsBoundaryPort. */
@Component
@Profile("google-maps & !test")
public final class GoogleMapsRoutingAdapter implements MapRoutingPort, RoutePreviewPort,
        PlaceAutocompletePort, GeocodingPort, ReverseGeocodingPort, DistanceMatrixPort {
    private final GoogleMapsBoundaryPort google;

    public GoogleMapsRoutingAdapter(GoogleMapsBoundaryPort google) {
        this.google = google;
    }

    @Override
    public RouteSnapshot preview(MapRouteRequest request) {
        GoogleMapsBoundaryPort.GoogleRoute route = google.calculate(request)
                .orElseThrow(() -> new SalesInvariantViolation("Google Maps route is unavailable"));
        return new RouteSnapshot("GOOGLE", route.reference(),
                request.warehouse().code() + " · " + request.warehouse().name(),
                request.address().label() + " · " + request.address().address().display(),
                route.distanceMeters(), route.durationSeconds(), route.previewUrl(),
                request.warehouse().latitude(), request.warehouse().longitude(),
                request.address().address().latitude(), request.address().address().longitude(),
                java.time.Instant.now(), "DRIVING", route.previewUrl());
    }

    @Override
    public List<PlaceSuggestion> search(String query) {
        return google.autocomplete(query);
    }

    @Override
    public Optional<GeocodedPlace> geocode(String query) {
        return google.geocode(query);
    }

    @Override
    public Optional<GeocodedPlace> reverseGeocode(MapCoordinate coordinate) {
        return google.reverseGeocode(coordinate);
    }

    @Override
    public Optional<DistanceEstimate> estimate(MapCoordinate origin, MapCoordinate destination) {
        return google.distance(origin, destination);
    }
}
