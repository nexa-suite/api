package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/catalog/promotions")
@Tag(name = "Catalog Promotions")
@SecurityRequirement(name = "bearerAuth")
public final class CatalogPromotionController {
    private final CatalogPromotionUseCase promotions;

    public CatalogPromotionController(CatalogPromotionUseCase promotions) { this.promotions = promotions; }

    @GetMapping
    public CatalogManagementModels.Page<CatalogManagementModels.PromotionView> list(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String status) { return promotions.promotions(CatalogHttpSupport.scope(context), page, size, status); }

    @GetMapping("/{id}")
    public ResponseEntity<CatalogManagementModels.PromotionView> detail(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) { var value = promotions.promotion(CatalogHttpSupport.scope(context), id); return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value); }

    @PostMapping
    public ResponseEntity<CatalogManagementModels.PromotionView> create(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody PromotionRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = promotions.create(CatalogHttpSupport.scope(context), request.slug(), request.name(), request.description(), request.discountType(), request.discountValue(), request.currency(), request.startsAt(), request.endsAt(), request.minimumQuantity(), request.stackingPolicy(), request.productIds(), request.categoryIds(), request.clientAccountIds(), request.rules(), idempotencyKey);
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<CatalogManagementModels.PromotionView> update(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody PromotionRequest request) {
        var value = promotions.update(CatalogHttpSupport.scope(context), id, request.slug(), request.name(), request.description(), request.discountType(), request.discountValue(), request.currency(), request.startsAt(), request.endsAt(), request.minimumQuantity(), request.stackingPolicy(), request.productIds(), request.categoryIds(), request.clientAccountIds(), request.rules(), CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PostMapping("/{id}/schedules")
    public ResponseEntity<CatalogManagementModels.PromotionView> schedule(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "SCHEDULED", ifMatch); }
    @PostMapping("/{id}/activations")
    public ResponseEntity<CatalogManagementModels.PromotionView> activate(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "ACTIVE", ifMatch); }
    @PostMapping("/{id}/pauses")
    public ResponseEntity<CatalogManagementModels.PromotionView> pause(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "PAUSED", ifMatch); }
    @PostMapping("/{id}/resumptions")
    public ResponseEntity<CatalogManagementModels.PromotionView> resume(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "ACTIVE", ifMatch); }
    @PostMapping("/{id}/cancellations")
    public ResponseEntity<CatalogManagementModels.PromotionView> cancel(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "CANCELLED", ifMatch); }
    @PostMapping("/{id}/expirations")
    public ResponseEntity<CatalogManagementModels.PromotionView> expire(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return status(context, id, "EXPIRED", ifMatch); }

    private ResponseEntity<CatalogManagementModels.PromotionView> status(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        var value = promotions.changeStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    public record PromotionRequest(String slug, String name, String description, String discountType, BigDecimal discountValue,
            String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy,
            List<UUID> productIds, List<UUID> categoryIds, List<UUID> clientAccountIds,
            List<CatalogManagementModels.PromotionRuleView> rules) {
        public PromotionRequest(String slug, String name, String description, String discountType, BigDecimal discountValue,
                String currency, Instant startsAt, Instant endsAt, BigDecimal minimumQuantity, String stackingPolicy,
                List<UUID> productIds, List<UUID> categoryIds) {
            this(slug, name, description, discountType, discountValue, currency, startsAt, endsAt, minimumQuantity,
                    stackingPolicy, productIds, categoryIds, List.of(), List.of());
        }
    }
}
