package com.nexa.api.salescommitment.presentation.purchaserequestdraft;

import com.nexa.api.catalogcommercialpolicy.presentation.rest.CatalogHttpSupport;
import com.nexa.api.salescommitment.application.exception.PurchaseRequestDraftPreconditionRequiredException;
import com.nexa.api.salescommitment.application.purchaserequestdraft.model.PurchaseRequestDraftModels;
import com.nexa.api.salescommitment.application.purchaserequestdraft.service.PurchaseRequestDraftServiceFacade;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/buyer/purchase-request-drafts")
@Tag(name = "Canonical Purchase Request Drafts")
@SecurityRequirement(name = "bearerAuth")
public final class PurchaseRequestDraftController {
    private static final String ACCESS = CatalogHttpSupport.ACCESS_CONTEXT;
    private final PurchaseRequestDraftServiceFacade service;
    public PurchaseRequestDraftController(PurchaseRequestDraftServiceFacade service) { this.service = service; }

    @PostMapping
    @Operation(operationId = "createPurchaseRequestDraft")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> create(@RequestAttribute(ACCESS) CurrentAccessContext context, @Valid @RequestBody CreateDraftRequest request) {
        PurchaseRequestDraftModels.DraftView value = service.create(context, request.clientAccountId(), request.requestedDeliveryDate());
        return ResponseEntity.created(URI.create("/api/v1/buyer/purchase-request-drafts/" + value.id())).eTag(etag(value.version())).body(value);
    }
    @GetMapping("/{draftId}")
    @Operation(operationId = "getPurchaseRequestDraft")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> get(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId) { var value = service.get(context, draftId); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
    @PutMapping("/{draftId}/lines")
    @Operation(operationId = "replacePurchaseRequestDraftLines")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> lines(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody LinesRequest request) { var value = service.replaceLines(context, draftId, version(ifMatch), request.lines().stream().map(line -> new PurchaseRequestDraftServiceFacade.LineCommand(line.skuId(), line.quantity(), line.unit(), line.notes())).toList()); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
    @PutMapping("/{draftId}/destination")
    @Operation(operationId = "setPurchaseRequestDraftDestination")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> destination(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody DestinationRequest request) { var value = service.setDestination(context, draftId, version(ifMatch), request.addressId()); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
    @PostMapping("/{draftId}/route-previews")
    @Operation(operationId = "previewPurchaseRequestDraftRoute")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> route(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody(required = false) RouteRequest request) { var value = service.previewRoute(context, draftId, version(ifMatch), request == null ? null : request.provider()); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
    @PutMapping("/{draftId}/preferences")
    @Operation(operationId = "setPurchaseRequestDraftPreferences")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> preferences(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @Valid @RequestBody PreferencesRequest request) { var value = service.setPreferences(context, draftId, version(ifMatch), request.paymentPreference(), request.requestedDeliveryDate()); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }
    @GetMapping("/{draftId}/review")
    @Operation(operationId = "reviewPurchaseRequestDraft")
    public PurchaseRequestDraftModels.ReviewView review(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId) { return service.review(context, draftId); }
    @PostMapping("/{draftId}/submissions")
    @Operation(operationId = "submitPurchaseRequestDraft")
    public ResponseEntity<PurchaseRequestDraftModels.DraftView> submit(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID draftId, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) { var value = service.submit(context, draftId, version(ifMatch), idempotencyKey); return ResponseEntity.ok().eTag(etag(value.version())).body(value); }

    private static String etag(long version) { return "\"" + version + "\""; }
    private static long version(String value) { if (value == null || value.isBlank()) throw new PurchaseRequestDraftPreconditionRequiredException(); try { return Long.parseLong(value.replace("\"", "").trim()); } catch (NumberFormatException e) { throw new PurchaseRequestDraftPreconditionRequiredException(); } }

    public record CreateDraftRequest(@NotNull UUID clientAccountId, @NotNull LocalDate requestedDeliveryDate) { }
    public record LinesRequest(@NotNull @Size(min = 1, max = 100) List<@Valid LineRequest> lines) { }
    public record LineRequest(@NotNull UUID skuId, @NotNull java.math.BigDecimal quantity, @Size(max = 32) String unit, @Size(max = 2000) String notes) { }
    public record DestinationRequest(@NotNull UUID addressId) { }
    public record RouteRequest(@Size(max = 40) String provider) { }
    public record PreferencesRequest(@NotNull @Size(max = 40) String paymentPreference, @NotNull LocalDate requestedDeliveryDate) { }
}
