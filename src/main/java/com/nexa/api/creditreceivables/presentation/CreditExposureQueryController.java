package com.nexa.api.creditreceivables.presentation;

import com.nexa.api.creditreceivables.application.service.CreditExposureApplicationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@Profile("!test")
@Validated
@RequestMapping("/api/v1/client-accounts")
@Tag(name = "Credit and Receivables")
@SecurityRequirement(name = "bearerAuth")
public class CreditExposureQueryController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final CreditExposureApplicationService service;

    public CreditExposureQueryController(CreditExposureApplicationService service) {
        this.service = service;
    }

    @GetMapping("/{clientAccountId}/credit-exposure")
    @Operation(operationId = "getClientCreditExposure",
            description = "Returns the current credit limit, ledger exposure, active reservations, outstanding receivables and available credit for an active customer account. Tenant and workspace scope come from the verified access context.")
    public CreditExposureApplicationService.CreditExposureView get(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID clientAccountId,
            @Parameter(description = "Three-letter currency code; defaults to PEN")
            @RequestParam(defaultValue = "PEN") @Pattern(regexp = "[A-Za-z]{3}") String currency) {
        return service.read(context, clientAccountId.toString(), currency);
    }
}
