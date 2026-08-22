package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.model.CatalogVariantModels;
import com.nexa.api.catalogmanagement.application.port.in.CatalogSkuUseCase;
import com.nexa.api.catalogmanagement.application.port.in.CatalogVariantUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogClientAccountPort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1")
@Tag(name = "Product Families and Sellable SKUs")
@SecurityRequirement(name = "bearerAuth")
public final class CatalogSkuController {
    private static final String ACCESS = CatalogHttpSupport.ACCESS_CONTEXT;
    private final CatalogSkuUseCase service;
    private final CatalogVariantUseCase variants;
    private final ObjectProvider<CatalogClientAccountPort> clientAccounts;

    public CatalogSkuController(CatalogSkuUseCase service, CatalogVariantUseCase variants) {
        this(service, variants, null);
    }

    @Autowired
    public CatalogSkuController(CatalogSkuUseCase service, CatalogVariantUseCase variants,
            ObjectProvider<CatalogClientAccountPort> clientAccounts) {
        this.service = service;
        this.variants = variants;
        this.clientAccounts = clientAccounts;
    }

    @GetMapping("/product-families")
    @Operation(operationId = "listProductFamilies")
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search) {
        return service.families(readScope(context), page, size, search);
    }
    @GetMapping("/product-families/{familyId}")
    @Operation(operationId = "getProductFamily")
    public CatalogSkuModels.FamilyView family(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId) { return service.family(readScope(context), familyId); }
    @PostMapping("/product-families")
    @Operation(operationId = "createProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> createFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestBody FamilyRequest request) {
        CatalogSkuModels.FamilyView value = service.createFamily(CatalogHttpSupport.scope(context), request.code(), request.name(), request.description(), request.categoryId(), request.brandId(), request.countryOfOrigin(), request.manufacturerReference(), request.supplierReference(), request.storageFamily());
        return ResponseEntity.created(URI.create("/api/v1/product-families/" + value.id())).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    @PostMapping("/product-families/{familyId}/activations")
    @Operation(operationId = "activateProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> activateFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) { return statusFamily(context, familyId, "ACTIVE", ifMatch); }
    @PostMapping("/product-families/{familyId}/deactivations")
    @Operation(operationId = "deactivateProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> deactivateFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) { return statusFamily(context, familyId, "INACTIVE", ifMatch); }

    @GetMapping("/product-families/{familyId}/skus")
    @Operation(operationId = "listFamilySkus")
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> familySkus(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search) { return service.skus(readScope(context), page, size, search, familyId); }
    @GetMapping("/product-families/{familyId}/variants")
    @Operation(operationId = "listProductFamilyVariants")
    public CatalogVariantModels.Page<CatalogVariantModels.VariantView> familyVariants(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID familyId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) {
        return variants.variants(readScope(context), familyId, page, size, search);
    }
    @PostMapping("/product-families/{familyId}/variants")
    @Operation(operationId = "createProductVariant")
    public ResponseEntity<CatalogVariantModels.VariantView> createVariant(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID familyId, @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody VariantRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        CatalogVariantModels.VariantView value = variants.create(CatalogHttpSupport.scope(context), familyId, request.code(), request.name(), request.description());
        return ResponseEntity.created(URI.create("/api/v1/product-variants/" + value.id())).body(value);
    }
    @GetMapping("/product-variants/{variantId}")
    @Operation(operationId = "getProductVariant")
    public CatalogVariantModels.VariantView variant(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID variantId) {
        return variants.variant(readScope(context), variantId);
    }
    @GetMapping("/product-variants/{variantId}/skus")
    @Operation(operationId = "listProductVariantSkus")
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> variantSkus(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @PathVariable UUID variantId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) {
        return variants.skus(readScope(context), variantId, page, size, search);
    }
    @GetMapping("/skus")
    @Operation(operationId = "listSellableSkus")
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search, @RequestParam(required = false) UUID familyId) { return service.skus(readScope(context), page, size, search, familyId); }
    @GetMapping("/skus/{skuId}")
    @Operation(operationId = "getSellableSku")
    public CatalogSkuModels.SkuView sku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.sku(readScope(context), skuId); }
    @PostMapping("/product-families/{familyId}/skus")
    @Operation(operationId = "createSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> createSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestBody SkuRequest request) {
        CatalogSkuModels.SkuView value = service.createSku(CatalogHttpSupport.scope(context), familyId, request.skuCode(), request.gtin(), request.presentation(), request.packagingType(), request.unitOfMeasure(), request.netWeight(), request.grossWeight(), request.packQuantity(), request.temperatureMin(), request.temperatureMax(), request.shelfLifeDays(), request.minimumRemainingShelfLifeDays(), request.lotTrackingRequired(), request.expiryTrackingRequired(), request.taxCategory());
        return ResponseEntity.created(URI.create("/api/v1/skus/" + value.id())).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    @PostMapping("/skus/{skuId}/deactivations")
    @Operation(operationId = "deactivateSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> deactivateSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) { return statusSku(context, skuId, "INACTIVE", ifMatch); }
    @PostMapping("/skus/{skuId}/activations")
    @Operation(operationId = "activateSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> activateSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId, @RequestHeader(HttpHeaders.IF_MATCH) String ifMatch) { return statusSku(context, skuId, "ACTIVE", ifMatch); }
    @PostMapping("/skus/{skuId}/prices")
    @Operation(operationId = "createSkuPrice")
    public ResponseEntity<CatalogSkuModels.PriceView> createPrice(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody PriceRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        CatalogSkuModels.PriceView value = service.createPrice(CatalogHttpSupport.scope(context), skuId, request.amount(), request.currency(), request.validFrom(), request.validUntil(), request.sourceCode(), request.sourceDescription(), idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/skus/" + skuId + "/prices/" + value.id())).body(value);
    }
    @GetMapping("/skus/{skuId}/prices")
    @Operation(operationId = "listSkuPrices")
    public List<CatalogSkuModels.PriceView> prices(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.prices(readScope(context), skuId); }
    @GetMapping("/skus/{skuId}/price-history")
    @Operation(operationId = "listSkuPriceHistory")
    public List<CatalogSkuModels.PriceView> priceHistory(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.prices(readScope(context), skuId); }

    private com.nexa.api.catalogmanagement.application.model.CatalogScope readScope(CurrentAccessContext context) {
        return CatalogHttpSupport.scope(context, clientAccounts);
    }

    private ResponseEntity<CatalogSkuModels.FamilyView> statusFamily(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        long version = CatalogHttpSupport.version(ifMatch);
        CatalogSkuModels.FamilyView value = service.changeFamilyStatus(CatalogHttpSupport.scope(context), id, status, version);
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    private ResponseEntity<CatalogSkuModels.SkuView> statusSku(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        CatalogSkuModels.SkuView value = service.changeSkuStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    public record FamilyRequest(String code, String name, String description, UUID categoryId, UUID brandId, String countryOfOrigin, String manufacturerReference, String supplierReference, String storageFamily) { }
    public record SkuRequest(String skuCode, String gtin, String presentation, String packagingType, String unitOfMeasure, BigDecimal netWeight, BigDecimal grossWeight, BigDecimal packQuantity, BigDecimal temperatureMin, BigDecimal temperatureMax, int shelfLifeDays, int minimumRemainingShelfLifeDays, boolean lotTrackingRequired, boolean expiryTrackingRequired, String taxCategory) { }
    public record VariantRequest(String code, String name, String description) { }
    public record PriceRequest(BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) { }
}
