package com.nexa.api.salescommitment.presentation.purchaserequest;

import com.nexa.api.salescommitment.application.model.FieldPurchaseRequestModels;
import com.nexa.api.salescommitment.application.service.FieldPurchaseRequestSubmissionService;
import com.nexa.api.salescommitment.presentation.SalesHttpHeaders;
import com.nexa.api.salescommitment.presentation.purchaserequest.mapper.PurchaseRequestHttpMapper;
import com.nexa.api.salescommitment.presentation.purchaserequest.response.PurchaseRequestDetailResponse;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/purchase-requests/field-submissions")
@Tag(name = "Purchase Requests")
@SecurityRequirement(name = "bearerAuth")
public class FieldPurchaseRequestController {
    private static final String ACCESS = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
    private final FieldPurchaseRequestSubmissionService service;
    private final PurchaseRequestHttpMapper mapper;
    public FieldPurchaseRequestController(FieldPurchaseRequestSubmissionService service, PurchaseRequestHttpMapper mapper) {
        this.service = service; this.mapper = mapper;
    }
    @PostMapping
    @Operation(operationId = "submitFieldPurchaseRequest", description = "Atomically creates and submits one internal sales Purchase Request using current customer and catalog terms. Replays the same scoped immutable intent.")
    public ResponseEntity<PurchaseRequestDetailResponse> submit(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody Request request) {
        var command = new FieldPurchaseRequestModels.Command(request.clientAccountId(), request.priority(),
                request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(),
                request.lines().stream().map(line -> new FieldPurchaseRequestModels.Line(line.catalogItemId(), line.quantity(),
                        line.unit(), line.notes(), line.expectedUnitPrice(), line.expectedCurrency())).toList());
        var result = service.submit(context, command, key);
        return ResponseEntity.status(201).eTag(SalesHttpHeaders.etag(result.version())).body(mapper.detail(result));
    }
    @Schema(name = "FieldPurchaseRequestSubmissionRequest")
    public record Request(@NotBlank @Size(max = 64) String clientAccountId, @Size(max = 32) String priority,
            @FutureOrPresent LocalDate requestedDeliveryDate, @Size(max = 2000) String deliveryProfileSnapshot,
            @Size(max = 80) String paymentOption, @Size(max = 2000) String comment,
            @NotEmpty @Size(max = 100) List<@NotNull @Valid Line> lines) { }
    @Schema(name = "FieldPurchaseRequestLine")
    public record Line(@NotBlank @Pattern(regexp = "(?i)CAT-[A-Z0-9-]{1,63}") String catalogItemId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotBlank @Size(max = 32) String unit, @Size(max = 2000) String notes,
            @NotNull @DecimalMin("0") BigDecimal expectedUnitPrice,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String expectedCurrency) { }
}
