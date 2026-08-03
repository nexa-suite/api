package com.nexa.api.sales.infrastructure.maps;

import com.nexa.api.sales.application.port.out.GoogleMapsBoundaryPort;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Google Maps adapter. Credentials, HTTP and SDK details stay behind GoogleMapsBoundaryPort. */
@Component
@Profile("google-maps & !test")
public final class GoogleMapsRoutingAdapter implements MapRoutingPort {
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
                route.distanceMeters(), route.durationSeconds(), route.previewUrl());
    }
}
