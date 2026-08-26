package com.nexa.api.businesstraceability.presentation;

import com.nexa.api.businesstraceability.application.model.AuditModels.AuditEventView;
import com.nexa.api.businesstraceability.application.model.AuditModels.AuditPage;
import com.nexa.api.businesstraceability.application.port.in.AuditViewerUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Tag(name = "Audit Viewer")
@SecurityRequirement(name = "bearerAuth")
public final class AuditViewerController {
	private static final String ACCESS_CONTEXT = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final AuditViewerUseCase audit;

	public AuditViewerController(AuditViewerUseCase audit) { this.audit = audit; }

	@GetMapping("/api/v1/audit-logs")
	@Operation(operationId = "listAuditLogs")
	public AuditPage list(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
		return audit.list(context, limit);
	}

	@GetMapping("/api/v1/audit-logs/{id}")
	@Operation(operationId = "getAuditLog")
	public AuditEventView detail(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context, @PathVariable String id) {
		return audit.detail(context, id);
	}
}
