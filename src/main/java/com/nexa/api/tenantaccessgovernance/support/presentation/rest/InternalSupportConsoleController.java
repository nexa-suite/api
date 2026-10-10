package com.nexa.api.tenantaccessgovernance.support.presentation.rest;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalOperatorContext;
import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.port.in.SupportConsoleUseCase;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderProjection;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Internal-only console operations, authenticated by the verified operator principal. */
@RestController
@RequestMapping("/api/v1/internal/console/support-requests")
@Tag(name = "Internal support console")
public final class InternalSupportConsoleController {
	private final SupportConsoleUseCase support;

	public InternalSupportConsoleController(SupportConsoleUseCase support) {
		this.support = support;
	}

	@GetMapping
	@Operation(operationId = "listInternalSupportRequests")
	public List<SupportRequestView> list(@AuthenticationPrincipal InternalOperatorContext operator,
			@RequestParam(defaultValue = "50") int limit) {
		return support.listInternal(operator.operatorId(), limit);
	}

	@PostMapping
	@Operation(operationId = "requestTemporarySalesOrderSupport")
	public ResponseEntity<SupportRequestView> request(@AuthenticationPrincipal InternalOperatorContext operator,
			@RequestBody CreateSupportRequest request) {
		SupportRequestView created = support.request(operator.operatorId(), request.tenantId(), request.workspaceId(),
			request.salesOrderId(), request.expiresAt());
		return ResponseEntity.status(201).body(created);
	}

	@PostMapping("/{requestId}/approval")
	@Operation(operationId = "approveTemporarySalesOrderSupport")
	public SupportRequestView approve(@AuthenticationPrincipal InternalOperatorContext operator,
			@PathVariable UUID requestId) {
		return support.approve(requestId, operator.operatorId());
	}

	@PostMapping("/{requestId}/revocation")
	@Operation(operationId = "revokeTemporarySalesOrderSupportByOperator")
	public SupportRequestView revoke(@AuthenticationPrincipal InternalOperatorContext operator,
			@PathVariable UUID requestId) {
		return support.revokeByOperator(requestId, operator.operatorId());
	}

	@GetMapping("/{requestId}/sales-order")
	@Operation(operationId = "readSalesOrderUnderSupportGrant")
	public SupportSalesOrderProjection readSalesOrder(@AuthenticationPrincipal InternalOperatorContext operator,
			@PathVariable UUID requestId) {
		return support.readSalesOrder(requestId, operator.operatorId());
	}

	public record CreateSupportRequest(UUID tenantId, UUID workspaceId, UUID salesOrderId, Instant expiresAt) { }
}
