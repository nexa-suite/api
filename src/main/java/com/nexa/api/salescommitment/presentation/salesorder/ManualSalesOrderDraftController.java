package com.nexa.api.salescommitment.presentation.salesorder;

import com.nexa.api.catalogcommercialpolicy.presentation.rest.CatalogHttpSupport;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftUseCase;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.presentation.SalesHttpHeaders;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** REST contract for the Sales four-step manual-order draft. */
@RestController
@Profile("!test")
@RequestMapping("/api/v1/sales-orders/manual-drafts")
@Tag(name = "Manual Sales Order Drafts")
@SecurityRequirement(name = "bearerAuth")
public final class ManualSalesOrderDraftController {
    private static final String ACCESS = CatalogHttpSupport.ACCESS_CONTEXT;
    private final ManualSalesOrderDraftUseCase drafts;

    public ManualSalesOrderDraftController(ManualSalesOrderDraftUseCase drafts) { this.drafts = drafts; }

    @PostMapping
    @Operation(operationId = "createManualSalesOrderDraft", summary = "Create or replay a manual Sales order draft")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> create(
            @RequestAttribute(ACCESS) CurrentAccessContext context,
            @Parameter(name = "Idempotency-Key", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var value = drafts.create(context, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/sales-orders/manual-drafts/" + value.id()))
                .eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @GetMapping("/{draftId}")
    @Operation(operationId = "getManualSalesOrderDraft")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> get(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId) {
        var value = drafts.get(context, draftId);
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @PutMapping("/{draftId}/client")
    @Operation(operationId = "updateManualSalesOrderDraftClient")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> client(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId,
            @Parameter(name = "If-Match", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ClientRequest request) {
        var value = drafts.updateClient(context, draftId, version(ifMatch), new ManualSalesOrderDraftModels.ClientCommand(
                request.clientAccountId(), request.requestedDeliveryDate(), request.priority(), request.paymentPreference(),
                request.currency(), request.notes()));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @PutMapping("/{draftId}/items")
    @Operation(operationId = "replaceManualSalesOrderDraftItems")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> items(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId,
            @Parameter(name = "If-Match", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ItemsRequest request) {
        var lines = request.lines().stream().map(line -> new ManualSalesOrderDraftModels.LineCommand(
                line.skuId(), line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList();
        var value = drafts.replaceLines(context, draftId, version(ifMatch), lines);
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @PutMapping("/{draftId}/delivery")
    @Operation(operationId = "updateManualSalesOrderDraftDelivery")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> delivery(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId,
            @Parameter(name = "If-Match", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody DeliveryRequest request) {
        var value = drafts.updateDelivery(context, draftId, version(ifMatch), new ManualSalesOrderDraftModels.DeliveryCommand(
                request.addressId(), request.deliveryNotes(), request.routeProvider()));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @GetMapping("/{draftId}/review")
    @Operation(operationId = "reviewManualSalesOrderDraft")
    public ResponseEntity<ManualSalesOrderDraftModels.ReviewView> review(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId) {
        var value = drafts.review(context, draftId);
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.draft().version())).body(value);
    }

    @PostMapping("/{draftId}/submissions")
    @Operation(operationId = "submitManualSalesOrderDraft")
    public ResponseEntity<ManualSalesOrderView> submit(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId,
            @Parameter(name = "If-Match", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @Parameter(name = "Idempotency-Key", required = true)
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        var value = drafts.submit(context, draftId, version(ifMatch), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/sales-orders/" + value.id()))
                .eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    @PostMapping("/{draftId}/abandonments")
    @Operation(operationId = "abandonManualSalesOrderDraft")
    public ResponseEntity<ManualSalesOrderDraftModels.DraftView> abandon(
            @RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId,
            @Parameter(name = "If-Match", required = true)
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        var value = drafts.abandon(context, draftId, version(ifMatch));
        return ResponseEntity.ok().eTag(SalesHttpHeaders.etag(value.version())).body(value);
    }

    private static long version(String value) { return SalesHttpHeaders.requireVersion(value); }

    public record ClientRequest(@NotNull UUID clientAccountId,
                                @NotNull @FutureOrPresent LocalDate requestedDeliveryDate,
                                @NotBlank @Size(max = 16) String priority,
                                @NotBlank @Size(max = 40) String paymentPreference,
                                @Size(max = 3) String currency,
                                @Size(max = 2000) String notes) { }

    public record ItemsRequest(@NotNull @Size(min = 1, max = 100) List<@Valid LineRequest> lines) { }

    public record LineRequest(UUID skuId, @Size(max = 64) String catalogItemId,
                              @NotNull java.math.BigDecimal quantity, @Size(max = 32) String unit,
                              @Size(max = 2000) String notes) { }

    public record DeliveryRequest(@NotNull UUID addressId, @Size(max = 2000) String deliveryNotes,
                                  @Size(max = 40) String routeProvider) { }
}
