package com.nexa.api.salescommitment.presentation.salesorder;

import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.presentation.SalesHttpHeaders;
import com.nexa.api.salescommitment.presentation.salesorder.mapper.SalesOrderHttpMapper;
import com.nexa.api.salescommitment.presentation.salesorder.request.ConversionNoteRequest;
import com.nexa.api.salescommitment.presentation.salesorder.request.RejectSalesOrderRequest;
import com.nexa.api.salescommitment.presentation.salesorder.response.SalesOrderResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
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
@Tag(name = "Sales Orders")
@SecurityRequirement(name = "bearerAuth")
public final class SalesOrderCommandController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final SalesOrderUseCase sales;
	private final SalesOrderHttpMapper mapper;
	public SalesOrderCommandController(SalesOrderUseCase sales, SalesOrderHttpMapper mapper) { this.sales = sales; this.mapper = mapper; }

	@PostMapping("/api/v1/purchase-requests/{purchaseRequestId}/order-conversions")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Sales Order created"),
			@ApiResponse(responseCode = "400", description = "Invalid conversion request"),
			@ApiResponse(responseCode = "401", description = "Authentication required"),
			@ApiResponse(responseCode = "403", description = "Sales or owner access required"),
			@ApiResponse(responseCode = "409", description = "Stale version or idempotency conflict")})
	public ResponseEntity<SalesOrderResponse> convert(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@PathVariable String purchaseRequestId, @RequestHeader(name = "If-Match") String ifMatch,
			@RequestHeader(name = "Idempotency-Key") String idempotencyKey,
			@Valid @RequestBody(required = false) ConversionNoteRequest request) {
		var value = sales.convert(context, purchaseRequestId, SalesHttpHeaders.requireVersion(ifMatch), idempotencyKey, request == null ? null : request.note());
		return ResponseEntity.status(201).eTag(SalesHttpHeaders.etag(value.version())).body(mapper.response(value));
	}

	@PostMapping("/api/v1/sales-orders/{id}/confirmations")
	public ResponseEntity<SalesOrderResponse> confirm(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) { return transition(context, id, "confirm", null, ifMatch, idempotencyKey); }
	@PostMapping("/api/v1/sales-orders/{id}/rejections")
	public ResponseEntity<SalesOrderResponse> reject(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody RejectSalesOrderRequest request) { return transition(context, id, "reject", request.reason(), ifMatch, idempotencyKey); }
	@PostMapping("/api/v1/sales-orders/{id}/cancellations")
	public ResponseEntity<SalesOrderResponse> cancel(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) { return transition(context, id, "cancel", null, ifMatch, idempotencyKey); }
	private ResponseEntity<SalesOrderResponse> transition(CurrentAccessContext context, String id, String action, String reason, String ifMatch, String idempotencyKey) {
		var value = sales.transition(context, id, action, reason, SalesHttpHeaders.requireVersion(ifMatch), idempotencyKey);
		return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.response(value));
	}
}
