package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogTaxonomyPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.brand.Brand;
import com.nexa.api.catalogcommercialpolicy.domain.model.brand.BrandId;
import com.nexa.api.catalogcommercialpolicy.domain.model.brand.BrandStatus;
import com.nexa.api.catalogcommercialpolicy.domain.model.category.Category;
import com.nexa.api.catalogcommercialpolicy.domain.model.category.CategoryId;
import com.nexa.api.catalogcommercialpolicy.domain.model.category.CategoryStatus;

import java.util.UUID;
import java.util.Objects;

public final class CatalogTaxonomyService implements CatalogTaxonomyUseCase {
    private final CatalogTaxonomyPort port;
    private final CatalogAuthorizationPort authorization;
    public CatalogTaxonomyService(CatalogTaxonomyPort port, CatalogAuthorizationPort authorization) {
        this.port = Objects.requireNonNull(port, "Catalog taxonomy port is required");
        this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
    }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(CatalogScope scope, int page, int size, String search) { authorization.require(CatalogPermissions.READ); return port.categories(scope, page, size, search); }
    @Override public CatalogManagementModels.CategoryView category(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.READ); return port.category(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("category")); }
    @Override public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description) {
        authorization.require(CatalogPermissions.MANAGE);
        Category category = Category.create(new CategoryId(UUID.randomUUID()), parentId == null ? null : new CategoryId(parentId), slug, name, description);
        category.activate();
        return port.createCategory(scope, parentId, category.slug(), category.name(), category.description());
    }
    @Override public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description, String idempotencyKey) {
        authorization.require(CatalogPermissions.MANAGE);
        Category category = Category.create(new CategoryId(UUID.randomUUID()), parentId == null ? null : new CategoryId(parentId), slug, name, description);
        category.activate();
        return port.createCategory(scope, parentId, category.slug(), category.name(), category.description(), idempotencyKey);
    }
    @Override public CatalogManagementModels.CategoryView updateCategory(CatalogScope scope, UUID id, UUID parentId, String slug, String name, String description, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogManagementModels.CategoryView current = port.category(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("category"));
        if (port.categoryWouldCreateCycle(scope, id, parentId)) throw new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConflictException("CATALOG_CATEGORY_CYCLE");
        Category category = Category.restore(new CategoryId(id), parentId == null ? null : new CategoryId(parentId), current.slug(), current.name(), current.description(), CategoryStatus.valueOf(current.status()));
        category.changeSlug(slug);
        category.rename(name);
        category.rewriteDescription(description);
        return port.updateCategory(scope, id, parentId, category.slug(), category.name(), category.description(), version);
    }
    @Override public CatalogManagementModels.CategoryView changeCategoryStatus(CatalogScope scope, UUID id, String status, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogManagementModels.CategoryView current = port.category(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("category"));
        Category category = Category.restore(new CategoryId(id), current.parentId() == null ? null : new CategoryId(UUID.fromString(current.parentId())), current.slug(), current.name(), current.description(), CategoryStatus.valueOf(current.status()));
        CategoryStatus target = CategoryStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        if (target != category.status()) {
            switch (target) {
                case ACTIVE -> category.activate();
                case INACTIVE -> category.deactivate();
                case ARCHIVED -> category.archive();
                case DRAFT -> throw new IllegalStateException("Category cannot return to DRAFT");
            }
        }
        return port.changeCategoryStatus(scope, id, target.name(), version);
    }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.BrandView> brands(CatalogScope scope, int page, int size, String search) { authorization.require(CatalogPermissions.READ); return port.brands(scope, page, size, search); }
    @Override public CatalogManagementModels.BrandView brand(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.READ); return port.brand(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("brand")); }
    @Override public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description) {
        authorization.require(CatalogPermissions.MANAGE);
        Brand brand = Brand.create(new BrandId(UUID.randomUUID()), slug, name, description);
        brand.activate();
        return port.createBrand(scope, brand.slug(), brand.name(), brand.description());
    }
    @Override public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description, String idempotencyKey) {
        authorization.require(CatalogPermissions.MANAGE);
        Brand brand = Brand.create(new BrandId(UUID.randomUUID()), slug, name, description);
        brand.activate();
        return port.createBrand(scope, brand.slug(), brand.name(), brand.description(), idempotencyKey);
    }
    @Override public CatalogManagementModels.BrandView updateBrand(CatalogScope scope, UUID id, String slug, String name, String description, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogManagementModels.BrandView current = port.brand(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("brand"));
        Brand brand = Brand.restore(new BrandId(id), current.slug(), current.name(), current.description(), BrandStatus.valueOf(current.status()));
        brand.changeSlug(slug);
        brand.rename(name);
        brand.rewriteDescription(description);
        return port.updateBrand(scope, id, brand.slug(), brand.name(), brand.description(), version);
    }
    @Override public CatalogManagementModels.BrandView changeBrandStatus(CatalogScope scope, UUID id, String status, long version) {
        authorization.require(CatalogPermissions.MANAGE);
        CatalogManagementModels.BrandView current = port.brand(scope, id).orElseThrow(() -> new com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException("brand"));
        Brand brand = Brand.restore(new BrandId(id), current.slug(), current.name(), current.description(), BrandStatus.valueOf(current.status()));
        BrandStatus target = BrandStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
        if (target != brand.status()) {
            switch (target) {
                case ACTIVE -> brand.activate();
                case INACTIVE -> brand.deactivate();
                case ARCHIVED -> brand.archive();
                case DRAFT -> throw new IllegalStateException("Brand cannot return to DRAFT");
            }
        }
        return port.changeBrandStatus(scope, id, target.name(), version);
    }
}
