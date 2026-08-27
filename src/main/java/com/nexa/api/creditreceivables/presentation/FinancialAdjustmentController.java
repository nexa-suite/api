package com.nexa.api.creditreceivables.presentation;

import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.service.FinancialAdjustmentApplicationService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.UUID;

/** BC-07 transport boundary; provider refund execution is not performed here. */
@RestController
@Profile("!test")
@Tag(name = "Credit and Receivables")
@SecurityRequirement(name = "bearerAuth")
public final class FinancialAdjustmentController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final FinancialAdjustmentApplicationService service;

    public FinancialAdjustmentController(FinancialAdjustmentApplicationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/receivables/{receivableId}/financial-adjustments")
    @Operation(operationId = "postPostPaymentFinancialAdjustment",
            description = "Posts an immutable reduction for a cancelled or reduced paid Sales Order. "
                    + "Creates a refund/customer-credit obligation when the adjustment creates overpayment; it does not call a provider.")
    public ResponseEntity<FinancialAdjustmentCommands.Result> postPostPayment(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID receivableId,
            @Parameter(required = true, description = "Receivable version used for optimistic concurrency")
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Parameter(required = true, description = "Stable retry key")
            @RequestHeader(name = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PostPaymentAdjustmentRequest request) {
        long expectedVersion = requireVersion(ifMatch);
        FinancialAdjustmentCommands.Result result = service.postPostPayment(context, receivableId, expectedVersion,
                idempotencyKey, request.toCommand());
        return ResponseEntity.ok().eTag(etag(result.receivableVersion())).body(result);
    }

    private static long requireVersion(String value) {
        if (value == null || !value.trim().matches("\\\"?\\d+\\\"?")) {
            throw new com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException("PRECONDITION_REQUIRED");
        }
        try {
            return Long.parseLong(value.replace("\"", "").trim());
        } catch (NumberFormatException exception) {
            throw new com.nexa.api.creditreceivables.application.exception.CreditReceivableOperationException("PRECONDITION_REQUIRED");
        }
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    public record PostPaymentAdjustmentRequest(
            @NotNull UUID salesOrderId,
            @NotNull UUID sourceId,
            @NotBlank @Size(max = 64) String sourceType,
            @Size(max = 16) String adjustmentKind,
            @Size(max = 8) String effect,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 2000) String reason,
            @Size(max = 16) String obligationType) {
        FinancialAdjustmentApplicationService.PostPaymentCommand toCommand() {
            return new FinancialAdjustmentApplicationService.PostPaymentCommand(salesOrderId, sourceId, sourceType,
                    adjustmentKind, effect, amount, currency, reason, obligationType);
        }
    }
}
