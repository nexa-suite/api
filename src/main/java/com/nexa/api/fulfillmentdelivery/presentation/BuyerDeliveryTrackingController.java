package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.EventView;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.Page;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/buyer/deliveries")
@Profile("!test")
@Tag(name = "Buyer delivery tracking")
@SecurityRequirement(name = "bearerAuth")
public final class BuyerDeliveryTrackingController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final FulfillmentDeliveryRequestRunner requests;

    public BuyerDeliveryTrackingController(FulfillmentDeliveryRequestRunner requests) {
        this.requests = requests;
    }

    @GetMapping
    @Operation(operationId = "listBuyerDeliveries")
    public PageResponse<DeliveryResponse> list(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        return page(requests.execute(context, FulfillmentDeliveryRequestRunner.Requirements.none(),
                composition -> composition.buyerTracking().list(context, page, size)));
    }

    @GetMapping("/{deliveryId}")
    @Operation(operationId = "getBuyerDelivery")
    public ResponseEntity<DeliveryResponse> detail(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                    @PathVariable UUID deliveryId) {
        DeliveryView value = requests.execute(context, FulfillmentDeliveryRequestRunner.Requirements.none(),
                composition -> composition.buyerTracking().detail(context, deliveryId));
        return ResponseEntity.ok().eTag(etag(value.version())).body(response(value));
    }

    @GetMapping("/{deliveryId}/events")
    @Operation(operationId = "listBuyerDeliveryEvents")
    public List<EventResponse> events(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                      @PathVariable UUID deliveryId) {
        return requests.execute(context, FulfillmentDeliveryRequestRunner.Requirements.none(),
                composition -> composition.buyerTracking().events(context, deliveryId)).stream()
                .map(event -> new EventResponse(event.type(), event.occurredAt())).toList();
    }

    private static PageResponse<DeliveryResponse> page(Page<DeliveryView> page) {
        return new PageResponse<>(page.items().stream().map(BuyerDeliveryTrackingController::response).toList(),
                page.page(), page.size(), page.total());
    }

    private static DeliveryResponse response(DeliveryView value) {
        return new DeliveryResponse(value.id(), value.salesOrderNumber(), value.status(), value.destination(),
                value.scheduledAt(), value.dispatchedAt(), value.deliveredAt(), value.proofOfDeliveryStatus(),
                value.version(), value.createdAt(), value.updatedAt());
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    public record PageResponse<T>(List<T> items, int page, int size, long total) { }
    public record DeliveryResponse(String id, String salesOrderNumber, String status, String destination,
                                   Instant scheduledAt, Instant dispatchedAt, Instant deliveredAt,
                                   String proofOfDeliveryStatus, long version, Instant createdAt,
                                   Instant updatedAt) { }
    public record EventResponse(String type, Instant occurredAt) { }
}
