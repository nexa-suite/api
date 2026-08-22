package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.infrastructure.maps.GoogleMapsHttpBoundaryAdapter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleMapsHttpBoundaryAdapterTest {
    @Test
    void mapsRoutesResponseToTheProviderBoundaryWithoutLeakingSdkTypes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/directions/v2:computeRoutes", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            apiKey.set(exchange.getRequestHeaders().getFirst("X-Goog-Api-Key"));
            byte[] body = "{\"routes\":[{\"distanceMeters\":3210,\"duration\":\"123.4s\",\"polyline\":{\"encodedPolyline\":\"abc\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            var adapter = new GoogleMapsHttpBoundaryAdapter(JsonMapper.shared(), "test-key",
                    URI.create("http://localhost:" + server.getAddress().getPort()), Duration.ofSeconds(2));

            var route = adapter.calculate(request()).orElseThrow();

            assertThat(route.distanceMeters()).isEqualTo(3210);
            assertThat(route.durationSeconds()).isEqualTo(124);
            assertThat(route.reference()).startsWith("GOOGLE-");
            assertThat(route.previewUrl()).contains("google.com/maps/dir");
            assertThat(apiKey).hasValue("test-key");
            assertThat(requestBody).hasValueSatisfying(value -> assertThat(value)
                    .contains("TRAFFIC_AWARE", "-12.04", "-77.03", "-12.05", "-77.04"));
        } finally {
            server.stop(0);
        }
    }

    private static MapRoutingPort.MapRouteRequest request() {
        var warehouse = new com.nexa.api.sales.domain.model.delivery.WarehouseSnapshot(
                "warehouse", "WH-01", "Warehouse", "Av. Arnaldo Márquez 1772",
                "PREFERRED_OPERATIONAL", "OPERATIONAL", 100, true, java.time.Instant.EPOCH,
                new BigDecimal("-12.0400"), new BigDecimal("-77.0300"));
        var address = new com.nexa.api.sales.domain.model.delivery.DeliveryAddressSnapshot(
                "address", "Pueblo Libre",
                new com.nexa.api.sales.domain.model.address.Address(
                        "AVENUE", "Av. Sucre 1992", null, "PE", "15", "1501", "150101",
                        null, null, "AVENUE", "Av. Sucre", "1992", null, null, null, null,
                        new BigDecimal("-12.0500"), new BigDecimal("-77.0400"), null, "MAP_PIN"), true);
        return new MapRoutingPort.MapRouteRequest(warehouse, address);
    }
}
