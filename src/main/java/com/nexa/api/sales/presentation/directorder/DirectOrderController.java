package com.nexa.api.sales.presentation.directorder;

import com.nexa.api.sales.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.sales.presentation.SalesHttpHeaders;
import com.nexa.api.sales.presentation.purchaserequest.request.CreatePurchaseRequestRequest;
import com.nexa.api.sales.presentation.salesorder.mapper.SalesOrderHttpMapper;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderResponse;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/direct-orders")
@Tag(name = "Direct Orders")
@SecurityRequirement(name = "bearerAuth")
public final class DirectOrderController {
    private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
    private final DirectOrderUseCase directOrders;
    private final SalesOrderHttpMapper mapper;

    public DirectOrderController(DirectOrderUseCase directOrders, SalesOrderHttpMapper mapper) {
        this.directOrders = directOrders;
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(operationId = "createDirectOrder", summary = "Create and confirm a Direct Order atomically")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Direct Order created and confirmed"),
            @ApiResponse(responseCode = "202", description = "Direct Order created and waiting for PREPAID confirmation"),
            @ApiResponse(responseCode = "409", description = "Commercial, availability, credit or idempotency conflict")
    })
    public ResponseEntity<SalesOrderResponse> create(
            @RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreatePurchaseRequestRequest request) {
        var value = directOrders.create(context, request.clientAccountId(), request.priority(), request.requestedDeliveryDate(),
                request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(),
                (request.lines() == null ? java.util.List.<com.nexa.api.sales.presentation.purchaserequest.request.PurchaseRequestLineRequest>of() : request.lines()).stream()
                        .map(line -> new DirectOrderUseCase.Line(line.catalogItemId(), line.quantity(), line.unit())).toList(),
                idempotencyKey);
        int status = "PENDING".equals(value.status()) ? 202 : 201;
        return ResponseEntity.status(status).eTag(SalesHttpHeaders.etag(value.version())).body(mapper.response(value));
    }
}
