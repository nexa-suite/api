package com.nexa.api.fulfillmentdelivery.presentation;

import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.Request;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchWindowPlanModels.View;
import com.nexa.api.fulfillmentdelivery.application.service.DispatchWindowPlanService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Dispatch plan command for a missing delivery window. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/fulfillments")
@Tag(name = "Fulfillment & Delivery")
@SecurityRequirement(name = "bearerAuth")
public final class DispatchWindowPlanController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final DispatchWindowPlanService service;

    public DispatchWindowPlanController(DispatchWindowPlanService service) {
        this.service = service;
    }

    @PostMapping("/{fulfillmentId}/dispatch-window-plans")
    @Operation(operationId = "planMissingFulfillmentDispatchWindow")
    public ResponseEntity<View> plan(@RequestAttribute(ACCESS) CurrentAccessContext context,
                                     @PathVariable UUID fulfillmentId,
                                     @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                     @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestBody Request request) {
        View value = service.plan(context, fulfillmentId, version(ifMatch), request, idempotencyKey);
        return ResponseEntity.status(value.replayed() ? 200 : 201)
                .eTag(etag(value.fulfillmentVersion())).body(value);
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

    private static String etag(long version) { return "\"" + version + "\""; }

    private static FulfillmentOperationException error(String code) {
        return new FulfillmentOperationException(code, false);
    }
}
