package com.nexa.api.tenantaccessgovernance.support.presentation.rest;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.port.in.SupportConsoleUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Consent and revocation must come from a freshly validated Company Owner access-context JWT. */
@RestController
@RequestMapping("/api/v1/tenant/support-consents")
@Tag(name = "Company Owner support consent")
@SecurityRequirement(name = "bearerAuth")
public final class CompanyOwnerSupportConsentController {
	private static final String CURRENT_ACCESS_CONTEXT =
		"com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final SupportConsoleUseCase support;

	public CompanyOwnerSupportConsentController(SupportConsoleUseCase support) {
		this.support = support;
	}

	@GetMapping
	@Operation(operationId = "listOwnerSupportConsentsAwaitingDecision")
	public List<SupportRequestView> list(@RequestAttribute(CURRENT_ACCESS_CONTEXT) CurrentAccessContext context) {
		return support.listOwner(context, 100);
	}

	@PostMapping("/{requestId}")
	@Operation(operationId = "consentToExactTemporarySupportScope")
	public SupportRequestView consent(@RequestAttribute(CURRENT_ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable UUID requestId) {
		return support.consent(requestId, context);
	}

	@PostMapping("/{requestId}/revocation")
	@Operation(operationId = "revokeTemporarySupportByCompanyOwner")
	public SupportRequestView revoke(@RequestAttribute(CURRENT_ACCESS_CONTEXT) CurrentAccessContext context,
			@PathVariable UUID requestId) {
		return support.revokeByOwner(requestId, context);
	}

}
