package com.nexa.api.sales.infrastructure.maps;

import com.nexa.api.sales.application.port.out.DistanceMatrixPort;
import com.nexa.api.sales.application.port.out.GeocodedPlace;
import com.nexa.api.sales.application.port.out.GeocodingPort;
import com.nexa.api.sales.application.port.out.MapCoordinate;
import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.application.port.out.PlaceAutocompletePort;
import com.nexa.api.sales.application.port.out.ReverseGeocodingPort;
import com.nexa.api.sales.application.port.out.RoutePreviewPort;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;

/** Stable local route preview used until an external map provider is configured. */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "nexa.maps.provider", havingValue = "local", matchIfMissing = true)
public final class LocalDeterministicMapAdapter implements MapRoutingPort, RoutePreviewPort,
        PlaceAutocompletePort, GeocodingPort, ReverseGeocodingPort, DistanceMatrixPort {
    @Override
    public RouteSnapshot preview(MapRouteRequest request) {
        if (request == null || request.warehouse() == null || request.address() == null) {
            throw new IllegalArgumentException("Map route request is required");
        }
        String input = request.warehouse().id() + "|" + request.warehouse().code() + "|"
                + request.address().id() + "|" + request.address().address().display();
        String digest = digest(input);
        long distance = 1_000L + Long.remainderUnsigned(Long.parseUnsignedLong(digest.substring(0, 16), 16), 90_000L);
        long duration = 300L + (distance * 60L / 24_000L);
        String reference = "LOCAL-" + digest.substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        return new RouteSnapshot("LOCAL_DETERMINISTIC", reference,
                request.warehouse().code() + " · " + request.warehouse().name(),
                request.address().label() + " · " + request.address().address().display(),
                distance, duration, "nexa://routes/" + reference,
                request.warehouse().latitude(), request.warehouse().longitude(),
                request.address().address().latitude(), request.address().address().longitude(),
                java.time.Instant.EPOCH, "DRIVING", "nexa://routes/" + reference);
    }

    @Override
    public List<PlaceSuggestion> search(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return List.of();
        return List.of(new PlaceSuggestion("LOCAL-PLACE-" + digest(normalized).substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                normalized, limaCoordinate()));
    }

    @Override
    public Optional<GeocodedPlace> geocode(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return Optional.empty();
        return Optional.of(new GeocodedPlace("LOCAL-GEOCODE-" + digest(normalized).substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                normalized, limaCoordinate(), normalized, "15", "1501", "150101"));
    }

    @Override
    public Optional<GeocodedPlace> reverseGeocode(MapCoordinate coordinate) {
        if (coordinate == null) return Optional.empty();
        String label = "Local pin " + coordinate.latitude().stripTrailingZeros().toPlainString()
                + ", " + coordinate.longitude().stripTrailingZeros().toPlainString();
        return Optional.of(new GeocodedPlace("LOCAL-PIN-" + digest(label).substring(0, 12).toUpperCase(java.util.Locale.ROOT),
                label, coordinate, label, "15", "1501", "150101"));
    }

    @Override
    public Optional<DistanceEstimate> estimate(MapCoordinate origin, MapCoordinate destination) {
        if (origin == null || destination == null) return Optional.empty();
        double latDistance = Math.toRadians(destination.latitude().doubleValue() - origin.latitude().doubleValue());
        double lonDistance = Math.toRadians(destination.longitude().doubleValue() - origin.longitude().doubleValue());
        double originLat = Math.toRadians(origin.latitude().doubleValue());
        double destinationLat = Math.toRadians(destination.latitude().doubleValue());
        double haversine = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2) * Math.cos(originLat) * Math.cos(destinationLat);
        long meters = Math.max(1L, Math.round(6_371_000D * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine))));
        return Optional.of(new DistanceEstimate(meters, Math.max(60L, meters * 60L / 24_000L), "LOCAL_DETERMINISTIC"));
    }

    private static MapCoordinate limaCoordinate() {
        return new MapCoordinate(new BigDecimal("-12.0464"), new BigDecimal("-77.0428"));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
