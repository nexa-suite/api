package com.nexa.api.sales.presentation.purchaserequest;

import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.sales.presentation.SalesHttpHeaders;
import com.nexa.api.sales.presentation.purchaserequest.mapper.PurchaseRequestHttpMapper;
import com.nexa.api.sales.presentation.purchaserequest.request.*;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestDetailResponse;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/purchase-requests")
@Profile("!test")
@Validated
@Tag(name = "Purchase Requests")
@SecurityRequirement(name = "bearerAuth")
public class PurchaseRequestCommandController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final PurchaseRequestUseCase sales;
	private final PurchaseRequestHttpMapper mapper;
	public PurchaseRequestCommandController(PurchaseRequestUseCase sales, PurchaseRequestHttpMapper mapper) { this.sales = sales; this.mapper = mapper; }

	@PostMapping
	@ApiResponses({@ApiResponse(responseCode = "201", description = "Purchase Request created", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "400", description = "Invalid request"), @ApiResponse(responseCode = "404", description = "Catalog Item or Client Account not found")})
	public ResponseEntity<PurchaseRequestDetailResponse> create(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @Valid @RequestBody CreatePurchaseRequestRequest request) {
		var value = sales.create(context, request.clientAccountId(), request.priority(), request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), mapper.requestedLines(request));
		return ResponseEntity.status(201).eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PatchMapping("/{id}")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Purchase Request updated", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<PurchaseRequestDetailResponse> update(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody UpdatePurchaseRequestRequest request) {
		var value = sales.update(context, id, request.priority(), request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), SalesHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PostMapping("/{id}/lines")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Line added", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match or duplicate line")})
	public ResponseEntity<PurchaseRequestDetailResponse> addLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody PurchaseRequestLineRequest request) {
		var value = sales.addLine(context, id, request.catalogItemId(), request.quantity(), request.unit(), request.notes(), SalesHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PatchMapping("/{id}/lines/{lineId}")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Line updated", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<PurchaseRequestDetailResponse> updateLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @PathVariable String lineId,
			@RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody UpdatePurchaseRequestLineRequest request) {
		var value = sales.updateLine(context, id, lineId, request.quantity(), request.notes(), SalesHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@DeleteMapping("/{id}/lines/{lineId}")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Line deleted", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "409", description = "Stale If-Match")})
	public ResponseEntity<PurchaseRequestDetailResponse> deleteLine(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @PathVariable String lineId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var value = sales.deleteLine(context, id, lineId, SalesHttpHeaders.requireVersion(ifMatch));
		return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}

	@PostMapping("/{id}/submissions")
	@ApiResponses({@ApiResponse(responseCode = "200", description = "Purchase Request submitted", headers = @Header(name = "ETag", description = "Current entity version")), @ApiResponse(responseCode = "400", description = "Idempotency-Key required"), @ApiResponse(responseCode = "409", description = "Stale If-Match or concurrent submission")})
	public ResponseEntity<PurchaseRequestDetailResponse> submit(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String key) { return transition(context, id, "submit", null, ifMatch, key); }
	@PostMapping("/{id}/reviews")
	public ResponseEntity<PurchaseRequestDetailResponse> review(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody(required = false) RequestAdjustmentRequest request) { return transition(context, id, "start-review", request == null ? null : request.reviewNote(), ifMatch, null); }
	@PostMapping("/{id}/adjustment-requests")
	public ResponseEntity<PurchaseRequestDetailResponse> requestAdjustment(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody(required = false) RequestAdjustmentRequest request) { return transition(context, id, "request-adjustment", request == null ? null : request.reviewNote(), ifMatch, null); }
	@PostMapping("/{id}/approvals")
	public ResponseEntity<PurchaseRequestDetailResponse> approve(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody(required = false) RequestAdjustmentRequest request) { return transition(context, id, "approve", request == null ? null : request.reviewNote(), ifMatch, null); }
	@PostMapping("/{id}/rejections")
	public ResponseEntity<PurchaseRequestDetailResponse> reject(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody(required = false) RejectPurchaseRequestRequest request) { return transition(context, id, "reject", request == null ? null : request.reviewNote(), ifMatch, null); }
	@PostMapping("/{id}/cancellations")
	public ResponseEntity<PurchaseRequestDetailResponse> cancel(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context, @PathVariable String id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return transition(context, id, "cancel", null, ifMatch, null); }

	private ResponseEntity<PurchaseRequestDetailResponse> transition(CurrentAccessContext context, String id, String action, String note, String ifMatch, String key) {
		var value = sales.transition(context, id, action, note, SalesHttpHeaders.requireVersion(ifMatch), key); return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(mapper.detail(value));
	}
}
