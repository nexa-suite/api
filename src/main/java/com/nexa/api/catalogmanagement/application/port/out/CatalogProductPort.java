package com.nexa.api.catalogmanagement.application.port.out;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;

import java.util.Optional;
import java.util.UUID;

public interface CatalogProductPort {
    CatalogManagementModels.Page<CatalogManagementModels.ProductView> products(CatalogScope scope, int page, int size, String search, String status);
    Optional<CatalogManagementModels.ProductView> product(CatalogScope scope, UUID id);
    CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode, String slug, String name, String description,
            UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath);
    default CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode, String slug, String name, String description,
            UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, String idempotencyKey) {
        return createProduct(scope, catalogItemId, productCode, slug, name, description, categoryId, brandId, storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath);
    }
    CatalogManagementModels.ProductView updateProduct(CatalogScope scope, UUID id, String slug, String name, String description,
            UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, long version);
    CatalogManagementModels.ProductView changeStatus(CatalogScope scope, UUID id, String status, long version);
}
