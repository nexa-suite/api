package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels;
import com.nexa.api.fulfillmentdelivery.application.model.DeliveryLoadModels.LoadView;
import com.nexa.api.fulfillmentdelivery.application.service.DeliveryLoadService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Dispatch load planning and whole-load assigned-driver acceptance. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class DeliveryLoadController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final DeliveryLoadService service;

    public DeliveryLoadController(DeliveryLoadService service) {
        this.service = service;
    }

    @GetMapping("/dispatch/loads")
    @Operation(operationId = "listDispatchDeliveryLoads")
    public ResponseEntity<List<LoadView>> listDispatch(@RequestAttribute(ACCESS) CurrentAccessContext context) {
        return ResponseEntity.ok().body(service.dispatchLoads(context));
    }

    @PostMapping("/dispatch/loads")
    @Operation(operationId = "createDispatchDeliveryLoad")
    public ResponseEntity<LoadView> create(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody DeliveryLoadModels.CreateLoadRequest request) {
        LoadView value = service.create(context, request, key);
        return ResponseEntity.status(201).eTag(etag(value.version())).body(value);
    }

    @GetMapping("/dispatch/loads/{loadId}")
    @Operation(operationId = "getDispatchDeliveryLoad")
    public ResponseEntity<LoadView> get(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                        @PathVariable UUID loadId) {
        LoadView value = service.getDispatchLoad(context, loadId);
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    @PutMapping("/dispatch/loads/{loadId}/stops")
    @Operation(operationId = "reorderDispatchDeliveryLoadStops")
    public ResponseEntity<LoadView> reorder(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                            @PathVariable UUID loadId,
                                            @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                            @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                            @RequestBody DeliveryLoadModels.ReorderStopsRequest request) {
        return mutation(service.reorder(context, loadId, version(ifMatch), request, key));
    }

    @PostMapping("/dispatch/loads/{loadId}/assignments")
    @Operation(operationId = "assignDispatchDeliveryLoadDriver")
    public ResponseEntity<LoadView> assign(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                           @PathVariable UUID loadId,
                                           @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key,
                                           @RequestBody DeliveryLoadModels.AssignDriverRequest request) {
        return mutation(service.assign(context, loadId, version(ifMatch), request, key));
    }

    @PostMapping("/dispatch/loads/{loadId}/offers")
    @Operation(operationId = "offerDispatchDeliveryLoadToDriver")
    public ResponseEntity<LoadView> offer(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                          @PathVariable UUID loadId,
                                          @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return mutation(service.offer(context, loadId, version(ifMatch), key));
    }

    @PostMapping("/dispatch/loads/{loadId}/handoff-confirmations")
    @Operation(operationId = "confirmDispatchDeliveryLoadHandoff")
    public ResponseEntity<LoadView> confirmHandoff(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                                    @PathVariable UUID loadId,
                                                    @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                    @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return mutation(service.confirmHandoff(context, loadId, version(ifMatch), key));
    }

    @GetMapping("/driver/loads")
    @Operation(operationId = "listCurrentDriverDeliveryLoads")
    public ResponseEntity<List<LoadView>> listDriver(@RequestAttribute(ACCESS) CurrentAccessContext context) {
        return ResponseEntity.ok().body(service.driverLoads(context));
    }

    @PostMapping("/driver/loads/{loadId}/acceptances")
    @Operation(operationId = "acceptAssignedDriverDeliveryLoad")
    public ResponseEntity<LoadView> accept(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                           @PathVariable UUID loadId,
                                           @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                           @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return mutation(service.accept(context, loadId, version(ifMatch), key));
    }

    private static ResponseEntity<LoadView> mutation(LoadView value) {
        return ResponseEntity.ok().eTag(etag(value.version())).body(value);
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static long version(String value) {
        if (value == null || value.isBlank()) throw error("PRECONDITION_REQUIRED");
        String candidate = value.trim();
        if (candidate.startsWith("W/") || candidate.length() < 2 || candidate.charAt(0) != '"'
                || candidate.charAt(candidate.length() - 1) != '"') throw error("VERSION_INVALID");
        try {
            long parsed = Long.parseLong(candidate.substring(1, candidate.length() - 1));
            if (parsed < 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw error("VERSION_INVALID");
        }
    }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code, false);
    }
}
