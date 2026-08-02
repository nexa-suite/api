package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogProductPort;
import com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException;

import java.util.UUID;

public final class CatalogProductService implements CatalogProductUseCase {
    private final CatalogProductPort port;
    private final CatalogAuthorizationPort authorization;
    public CatalogProductService(CatalogProductPort port, CatalogAuthorizationPort authorization) { this.port = port; this.authorization = authorization; }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.ProductView> products(CatalogScope scope, int page, int size, String search, String status) { authorization.require(CatalogPermissions.READ); return port.products(scope, page, size, search, status); }
    @Override public CatalogManagementModels.ProductView product(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.READ); return port.product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product")); }
    @Override public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode, String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath) { authorization.require(CatalogPermissions.MANAGE); return port.createProduct(scope, catalogItemId, productCode, slug, name, description, categoryId, brandId, storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath); }
    @Override public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode, String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, String idempotencyKey) { authorization.require(CatalogPermissions.MANAGE); return port.createProduct(scope, catalogItemId, productCode, slug, name, description, categoryId, brandId, storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath, idempotencyKey); }
    @Override public CatalogManagementModels.ProductView updateProduct(CatalogScope scope, UUID id, String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, long version) { authorization.require(CatalogPermissions.MANAGE); return port.updateProduct(scope, id, slug, name, description, categoryId, brandId, storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath, version); }
    @Override public CatalogManagementModels.ProductView changeStatus(CatalogScope scope, UUID id, String status, long version) { authorization.require(CatalogPermissions.MANAGE); return port.changeStatus(scope, id, status, version); }
}
