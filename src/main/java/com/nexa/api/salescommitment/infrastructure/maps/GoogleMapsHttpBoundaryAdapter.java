package com.nexa.api.salescommitment.infrastructure.maps;

import com.nexa.api.salescommitment.application.port.out.GoogleMapsBoundaryPort;
import com.nexa.api.salescommitment.application.port.out.MapRoutingPort;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Server-side Google Routes API adapter. The Sales module only sees
 * {@link GoogleMapsBoundaryPort}; API keys and HTTP payloads stay here.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(name = "nexa.maps.provider", havingValue = "google")
public final class GoogleMapsHttpBoundaryAdapter implements GoogleMapsBoundaryPort {
    private final ObjectMapper mapper;
    private final String apiKey;
    private final URI routesEndpoint;
    private final HttpClient httpClient;
    private final Duration requestTimeout;

    public GoogleMapsHttpBoundaryAdapter(ObjectMapper mapper, Environment environment) {
        this(mapper,
                required(environment, "nexa.maps.google.api-key", "NEXA_MAPS_GOOGLE_API_KEY"),
                URI.create(environment.getProperty("nexa.maps.google.routes-base-url", "https://routes.googleapis.com")),
                duration(environment, "nexa.maps.google.timeout-ms", 10_000L));
    }

    public GoogleMapsHttpBoundaryAdapter(ObjectMapper mapper, String apiKey, URI routesBaseUrl, Duration requestTimeout) {
        this.mapper = mapper;
        this.apiKey = required(apiKey, "Google Maps API key");
        this.routesEndpoint = URI.create(trimTrailingSlash(routesBaseUrl.toString()) + "/directions/v2:computeRoutes");
        this.requestTimeout = requestTimeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
    }

    @Override
    public Optional<GoogleRoute> calculate(MapRoutingPort.MapRouteRequest request) {
        if (!hasCoordinates(request)) return Optional.empty();
        try {
            String payload = mapper.writeValueAsString(routePayload(request));
            HttpRequest httpRequest = HttpRequest.newBuilder(routesEndpoint)
                    .timeout(requestTimeout)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", "routes.distanceMeters,routes.duration,routes.polyline.encodedPolyline")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return Optional.empty();
            return route(response.body(), request);
        } catch (IOException exception) {
            return Optional.empty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<GoogleRoute> route(String body, MapRoutingPort.MapRouteRequest request) {
        JsonNode route = mapper.readTree(body).path("routes").path(0);
        if (route.isMissingNode() || !route.has("distanceMeters") || !route.has("duration")) return Optional.empty();
        long distanceMeters = route.path("distanceMeters").asLong(-1L);
        long durationSeconds = durationSeconds(route.path("duration").asText(""));
        if (distanceMeters < 0 || durationSeconds < 0) return Optional.empty();
        String polyline = route.path("polyline").path("encodedPolyline").asText("");
        String reference = "GOOGLE-" + digest(request, polyline).substring(0, 16).toUpperCase(java.util.Locale.ROOT);
        return Optional.of(new GoogleRoute(reference, distanceMeters, durationSeconds, previewUrl(request)));
    }

    private Map<String, Object> routePayload(MapRoutingPort.MapRouteRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("origin", waypoint(request.warehouse().latitude(), request.warehouse().longitude()));
        payload.put("destination", waypoint(request.address().address().latitude(), request.address().address().longitude()));
        payload.put("travelMode", "DRIVE");
        payload.put("routingPreference", "TRAFFIC_AWARE");
        payload.put("computeAlternativeRoutes", false);
        payload.put("units", "METRIC");
        return payload;
    }

    private static Map<String, Object> waypoint(BigDecimal latitude, BigDecimal longitude) {
        return Map.of("location", Map.of("latLng", Map.of("latitude", latitude, "longitude", longitude)));
    }

    private static boolean hasCoordinates(MapRoutingPort.MapRouteRequest request) {
        return request != null && request.warehouse() != null && request.address() != null
                && request.warehouse().latitude() != null && request.warehouse().longitude() != null
                && request.address().address().latitude() != null && request.address().address().longitude() != null;
    }

    private static long durationSeconds(String value) {
        if (value == null || !value.endsWith("s")) return -1L;
        try {
            return new BigDecimal(value.substring(0, value.length() - 1))
                    .setScale(0, RoundingMode.CEILING).longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            return -1L;
        }
    }

    private static String previewUrl(MapRoutingPort.MapRouteRequest request) {
        return "https://www.google.com/maps/dir/?api=1&origin="
                + request.warehouse().latitude().toPlainString() + "," + request.warehouse().longitude().toPlainString()
                + "&destination=" + request.address().address().latitude().toPlainString() + ","
                + request.address().address().longitude().toPlainString() + "&travelmode=driving";
    }

    private static String digest(MapRoutingPort.MapRouteRequest request, String polyline) {
        try {
            String input = request.warehouse().id() + "|" + request.address().id() + "|" + polyline;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static Duration duration(Environment environment, String key, long fallbackMillis) {
        String value = environment.getProperty(key);
        try {
            return Duration.ofMillis(value == null || value.isBlank() ? fallbackMillis : Long.parseLong(value));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Invalid duration for " + key, exception);
        }
    }

    private static String required(Environment environment, String property, String envName) {
        return required(environment.getProperty(property, environment.getProperty(envName, "")), property);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalStateException(label + " is required when Google Maps is enabled");
        return value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String normalized = required(value, "Google Maps routes base URL");
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
