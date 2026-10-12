package com.nexa.api.catalogcommercialpolicy.presentation.rest;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogCommercialPolicyRequestPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests;

    public CatalogManagementController(CatalogTaxonomyUseCase taxonomy, CatalogProductUseCase products) {
        this(taxonomy, products, null);
    }

    @Autowired
    public CatalogManagementController(CatalogTaxonomyUseCase taxonomy, CatalogProductUseCase products,
            ObjectProvider<TenantCatalogCommercialPolicyRequestPort> tenantRequests) {
        this.taxonomy = taxonomy;
        this.products = products;
        this.tenantRequests = tenantRequests;
    }

    @GetMapping("/categories")
    public CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.categories(CatalogHttpSupport.scope(context), page, size, search),
                request -> request.taxonomy().categories(request.scope(), page, size, search));
    }

    @GetMapping("/categories/{id}")
    public CatalogManagementModels.CategoryView category(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.category(CatalogHttpSupport.scope(context), id),
                request -> request.taxonomy().category(request.scope(), id));
    }

    @PostMapping("/categories")
    public ResponseEntity<CatalogManagementModels.CategoryView> createCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody CategoryRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.createCategory(CatalogHttpSupport.scope(context), CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), idempotencyKey),
                scoped -> scoped.taxonomy().createCategory(scoped.scope(), CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), idempotencyKey));
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PatchMapping("/categories/{id}")
    public ResponseEntity<CatalogManagementModels.CategoryView> updateCategory(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody CategoryRequest request) {
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.updateCategory(CatalogHttpSupport.scope(context), id, CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch)),
                scoped -> scoped.taxonomy().updateCategory(scoped.scope(), id, CatalogHttpSupport.uuid(request.parentId()), request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch)));
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
            @RequestParam(required = false) String search) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.brands(CatalogHttpSupport.scope(context), page, size, search),
                request -> request.taxonomy().brands(request.scope(), page, size, search));
    }

    @GetMapping("/brands/{id}")
    public CatalogManagementModels.BrandView brand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) {
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.brand(CatalogHttpSupport.scope(context), id),
                request -> request.taxonomy().brand(request.scope(), id));
    }

    @PostMapping("/brands")
    public ResponseEntity<CatalogManagementModels.BrandView> createBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey, @RequestBody BrandRequest request) {
        CatalogHttpSupport.requireIdempotency(idempotencyKey);
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.createBrand(CatalogHttpSupport.scope(context), request.slug(), request.name(), request.description(), idempotencyKey),
                scoped -> scoped.taxonomy().createBrand(scoped.scope(), request.slug(), request.name(), request.description(), idempotencyKey));
        return ResponseEntity.status(201).eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    @PatchMapping("/brands/{id}")
    public ResponseEntity<CatalogManagementModels.BrandView> updateBrand(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id, @RequestHeader(name = "If-Match", required = false) String ifMatch, @RequestBody BrandRequest request) {
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.updateBrand(CatalogHttpSupport.scope(context), id, request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch)),
                scoped -> scoped.taxonomy().updateBrand(scoped.scope(), id, request.slug(), request.name(), request.description(), CatalogHttpSupport.version(ifMatch)));
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
        return CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> products.products(CatalogHttpSupport.scope(context), page, size, search, status),
                request -> request.products().products(request.scope(), page, size, search, status));
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<CatalogManagementModels.ProductView> product(@RequestAttribute(CatalogHttpSupport.ACCESS_CONTEXT) CurrentAccessContext context,
            @PathVariable UUID id) {
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> products.product(CatalogHttpSupport.scope(context), id),
                request -> request.products().product(request.scope(), id));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }

    private ResponseEntity<CatalogManagementModels.CategoryView> categoryStatus(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.changeCategoryStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch)),
                request -> request.taxonomy().changeCategoryStatus(request.scope(), id, status, CatalogHttpSupport.version(ifMatch)));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    private ResponseEntity<CatalogManagementModels.BrandView> brandStatus(CurrentAccessContext context, UUID id, String status, String ifMatch) {
        var value = CatalogHttpSupport.tenantRequest(context, tenantRequests,
                () -> taxonomy.changeBrandStatus(CatalogHttpSupport.scope(context), id, status, CatalogHttpSupport.version(ifMatch)),
                request -> request.taxonomy().changeBrandStatus(request.scope(), id, status, CatalogHttpSupport.version(ifMatch)));
        return ResponseEntity.ok().eTag(CatalogHttpSupport.etag(value.version())).body(value);
    }
    public record CategoryRequest(String parentId, String slug, String name, String description) { }
    public record BrandRequest(String slug, String name, String description) { }
}
