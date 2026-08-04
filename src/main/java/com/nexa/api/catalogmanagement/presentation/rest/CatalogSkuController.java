package com.nexa.api.catalogmanagement.presentation.rest;

import com.nexa.api.catalogmanagement.application.model.CatalogSkuModels;
import com.nexa.api.catalogmanagement.application.service.CatalogSkuServiceFacade;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
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
    private final CatalogSkuServiceFacade service;
    public CatalogSkuController(CatalogSkuServiceFacade service) { this.service = service; }

    @GetMapping("/product-families")
    @Operation(operationId = "listProductFamilies")
    public CatalogSkuModels.Page<CatalogSkuModels.FamilyView> families(@RequestAttribute(ACCESS) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search) {
        return service.families(CatalogHttpSupport.scope(context), page, size, search);
    }
    @GetMapping("/product-families/{familyId}")
    @Operation(operationId = "getProductFamily")
    public CatalogSkuModels.FamilyView family(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId) { return service.family(CatalogHttpSupport.scope(context), familyId); }
    @PostMapping("/product-families")
    @Operation(operationId = "createProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> createFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestBody FamilyRequest request) {
        CatalogSkuModels.FamilyView value = service.createFamily(CatalogHttpSupport.scope(context), request.code(), request.name(), request.description(), request.categoryId(), request.brandId(), request.countryOfOrigin(), request.manufacturerReference(), request.supplierReference(), request.storageFamily());
        return ResponseEntity.created(URI.create("/api/v1/product-families/" + value.id())).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    @PostMapping("/product-families/{familyId}/activations")
    @Operation(operationId = "activateProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> activateFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return statusFamily(context, familyId, "ACTIVE", ifMatch); }
    @PostMapping("/product-families/{familyId}/deactivations")
    @Operation(operationId = "deactivateProductFamily")
    public ResponseEntity<CatalogSkuModels.FamilyView> deactivateFamily(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return statusFamily(context, familyId, "INACTIVE", ifMatch); }

    @GetMapping("/product-families/{familyId}/skus")
    @Operation(operationId = "listFamilySkus")
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> familySkus(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search) { return service.skus(CatalogHttpSupport.scope(context), page, size, search, familyId); }
    @GetMapping("/skus")
    @Operation(operationId = "listSellableSkus")
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(@RequestAttribute(ACCESS) CurrentAccessContext context, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String search, @RequestParam(required = false) UUID familyId) { return service.skus(CatalogHttpSupport.scope(context), page, size, search, familyId); }
    @GetMapping("/skus/{skuId}")
    @Operation(operationId = "getSellableSku")
    public CatalogSkuModels.SkuView sku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.sku(CatalogHttpSupport.scope(context), skuId); }
    @PostMapping("/product-families/{familyId}/skus")
    @Operation(operationId = "createSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> createSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID familyId, @RequestBody SkuRequest request) {
        CatalogSkuModels.SkuView value = service.createSku(CatalogHttpSupport.scope(context), familyId, request.skuCode(), request.gtin(), request.presentation(), request.packagingType(), request.unitOfMeasure(), request.netWeight(), request.grossWeight(), request.packQuantity(), request.temperatureMin(), request.temperatureMax(), request.shelfLifeDays(), request.minimumRemainingShelfLifeDays(), request.lotTrackingRequired(), request.expiryTrackingRequired(), request.taxCategory());
        return ResponseEntity.created(URI.create("/api/v1/skus/" + value.id())).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    @PostMapping("/skus/{skuId}/deactivations")
    @Operation(operationId = "deactivateSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> deactivateSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return statusSku(context, skuId, "INACTIVE", ifMatch); }
    @PostMapping("/skus/{skuId}/activations")
    @Operation(operationId = "activateSellableSku")
    public ResponseEntity<CatalogSkuModels.SkuView> activateSku(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return statusSku(context, skuId, "ACTIVE", ifMatch); }
    @PostMapping("/skus/{skuId}/prices")
    @Operation(operationId = "createSkuPrice")
    public ResponseEntity<CatalogSkuModels.PriceView> createPrice(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId, @RequestBody PriceRequest request) {
        CatalogSkuModels.PriceView value = service.createPrice(CatalogHttpSupport.scope(context), skuId, request.amount(), request.currency(), request.validFrom(), request.validUntil(), request.sourceCode(), request.sourceDescription());
        return ResponseEntity.created(URI.create("/api/v1/skus/" + skuId + "/prices/" + value.id())).body(value);
    }
    @GetMapping("/skus/{skuId}/prices")
    @Operation(operationId = "listSkuPrices")
    public List<CatalogSkuModels.PriceView> prices(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.prices(CatalogHttpSupport.scope(context), skuId); }
    @GetMapping("/skus/{skuId}/price-history")
    @Operation(operationId = "listSkuPriceHistory")
    public List<CatalogSkuModels.PriceView> priceHistory(@RequestAttribute(ACCESS) CurrentAccessContext context, @PathVariable UUID skuId) { return service.prices(CatalogHttpSupport.scope(context), skuId); }

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
    public record PriceRequest(BigDecimal amount, String currency, Instant validFrom, Instant validUntil, String sourceCode, String sourceDescription) { }
}
