package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
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

import java.util.UUID;

@RestController
@Profile("!test")
@RequestMapping("/api/v1/catalog")
@Tag(name = "Catalog Management")
@SecurityRequirement(name = "bearerAuth")
public final class CatalogManagementController {
    private final CatalogTaxonomyUseCase taxonomy;
    private final CatalogProductUseCase products;

    public CatalogManagementController(CatalogTaxonomyUseCase taxonomy, CatalogProductUseCase products) {
        this.taxonomy = taxonomy;
        this.products = products;
    }

    @GetMapping("/categories")
    public CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) {
        return taxonomy.categories(CatalogHttpSupport.scope(context), page, size, search);
    }

    @GetMapping("/categories/{id}")
    public CatalogManagementModels.CategoryView category(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) { return taxonomy.category(CatalogHttpSupport.scope(context), id); }

    @PostMapping("/categories")
    public ResponseEntity<CatalogManagementModels.CategoryView> createCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CategoryRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = taxonomy.createCategory(CatalogHttpSupport.scope(context), CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), idempotencyKey);
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PatchMapping("/categories/{id}")
    public ResponseEntity<CatalogManagementModels.CategoryView> updateCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody CategoryRequest request) {
        var value = taxonomy.updateCategory(CatalogHttpSupport.scope(context), id, CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PostMapping("/categories/{id}/activations")
    public ResponseEntity<CatalogManagementModels.CategoryView> activateCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return categoryStatus(context, id, "ACTIVE", ifMatch); }

    @PostMapping("/categories/{id}/deactivations")
    public ResponseEntity<CatalogManagementModels.CategoryView> deactivateCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return categoryStatus(context, id, "INACTIVE", ifMatch); }

    @GetMapping("/brands")
    public CatalogManagementModels.Page<CatalogManagementModels.BrandView> brands(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) { return taxonomy.brands(CatalogHttpSupport.scope(context), page, size, search); }

    @GetMapping("/brands/{id}")
    public CatalogManagementModels.BrandView brand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) { return taxonomy.brand(CatalogHttpSupport.scope(context), id); }

    @PostMapping("/brands")
    public ResponseEntity<CatalogManagementModels.BrandView> createBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody BrandRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = taxonomy.createBrand(CatalogHttpSupport.scope(context), request.slug(), request.name(), request.description(), idempotencyKey);
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PatchMapping("/brands/{id}")
    public ResponseEntity<CatalogManagementModels.BrandView> updateBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody BrandRequest request) {
        var value = taxonomy.updateBrand(CatalogHttpSupport.scope(context), id, request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PostMapping("/brands/{id}/activations")
    public ResponseEntity<CatalogManagementModels.BrandView> activateBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return brandStatus(context, id, "ACTIVE", ifMatch); }

    @PostMapping("/brands/{id}/deactivations")
    public ResponseEntity<CatalogManagementModels.BrandView> deactivateBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch) { return brandStatus(context, id, "INACTIVE", ifMatch); }

    @GetMapping("/products")
    public CatalogManagementModels.Page<CatalogManagementModels.ProductView> productList(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search, @RequestParam(required = false) String status) {
        return products.products(CatalogHttpSupport.scope(context), page, size, search, status);
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<CatalogManagementModels.ProductView> product(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) { var value = products.product(CatalogHttpSupport.scope(context), id); return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value); }

    private ResponseEntity<CatalogManagementModels.CategoryView> categoryStatus(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        var value = taxonomy.changeCategoryStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    private ResponseEntity<CatalogManagementModels.BrandView> brandStatus(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        var value = taxonomy.changeBrandStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    public record CategoryRequest(String parentId, String slug, String name, String description) { }
    public record BrandRequest(String slug, String name, String description) { }
}
