package com.nexa.api.tenantaccessgovernance.support.presentation.rest;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalOperatorContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalConsoleOnboardingHealth;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.InternalConsoleOnboardingHealthQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Bounded onboarding health for authenticated internal operators; no contact or locator data. */
@RestController
@RequestMapping("/api/v1/internal/console/onboarding")
@Tag(name = "Internal onboarding console")
public final class InternalConsoleOnboardingController {
	private final InternalConsoleOnboardingHealthQuery onboarding;

	public InternalConsoleOnboardingController(InternalConsoleOnboardingHealthQuery onboarding) {
		this.onboarding = onboarding;
	}

	@GetMapping
	@Operation(operationId = "listInternalOnboardingHealth")
	public List<InternalConsoleOnboardingHealth> list(
			@AuthenticationPrincipal InternalOperatorContext operator,
			@RequestParam(defaultValue = "50") int limit) {
		return onboarding.listRecent(operator.operatorId(), limit);
	}
}
