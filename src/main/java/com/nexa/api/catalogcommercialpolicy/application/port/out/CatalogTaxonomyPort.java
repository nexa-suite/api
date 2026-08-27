package com.nexa.api.catalogcommercialpolicy.application.port.out;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;

import java.util.Optional;
import java.util.UUID;

public interface CatalogTaxonomyPort {
    CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(CatalogScope scope, int page, int size, String search);
    CatalogManagementModels.CategoryView changeCategoryStatus(CatalogScope scope, UUID id, String status, long version);
    CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description);
    default CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description, String idempotencyKey) { return createCategory(scope, parentId, slug, name, description); }
    CatalogManagementModels.CategoryView updateCategory(CatalogScope scope, UUID id, UUID parentId, String slug, String name, String description, long version);
    default boolean categoryWouldCreateCycle(CatalogScope scope, UUID id, UUID parentId) { return id != null && id.equals(parentId); }
    CatalogManagementModels.Page<CatalogManagementModels.BrandView> brands(CatalogScope scope, int page, int size, String search);
    CatalogManagementModels.BrandView changeBrandStatus(CatalogScope scope, UUID id, String status, long version);
    CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description);
    default CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description, String idempotencyKey) { return createBrand(scope, slug, name, description); }
    CatalogManagementModels.BrandView updateBrand(CatalogScope scope, UUID id, String slug, String name, String description, long version);
    Optional<CatalogManagementModels.CategoryView> category(CatalogScope scope, UUID id);
    Optional<CatalogManagementModels.BrandView> brand(CatalogScope scope, UUID id);
}
