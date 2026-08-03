package com.nexa.api.sales.infrastructure.maps;

import com.nexa.api.sales.application.port.out.MapRoutingPort;
import com.nexa.api.sales.domain.model.delivery.RouteSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stable local route preview used until an external map provider is configured. */
@Component
@Profile("!test & !google-maps")
public final class LocalDeterministicMapAdapter implements MapRoutingPort {
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
                distance, duration, "nexa://routes/" + reference);
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
