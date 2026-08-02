package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.model.CatalogManagementModels;
import com.nexa.api.catalogmanagement.application.model.CatalogScope;
import com.nexa.api.catalogmanagement.application.CatalogPermissions;
import com.nexa.api.catalogmanagement.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogTaxonomyPort;

import java.util.UUID;

public final class CatalogTaxonomyService implements CatalogTaxonomyUseCase {
    private final CatalogTaxonomyPort port;
    private final CatalogAuthorizationPort authorization;
    public CatalogTaxonomyService(CatalogTaxonomyPort port, CatalogAuthorizationPort authorization) { this.port = port; this.authorization = authorization; }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.CategoryView> categories(CatalogScope scope, int page, int size, String search) { authorization.require(CatalogPermissions.READ); return port.categories(scope, page, size, search); }
    @Override public CatalogManagementModels.CategoryView category(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.READ); return port.category(scope, id).orElseThrow(() -> new com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException("category")); }
    @Override public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description) { authorization.require(CatalogPermissions.MANAGE); return port.createCategory(scope, parentId, slug, name, description); }
    @Override public CatalogManagementModels.CategoryView createCategory(CatalogScope scope, UUID parentId, String slug, String name, String description, String idempotencyKey) { authorization.require(CatalogPermissions.MANAGE); return port.createCategory(scope, parentId, slug, name, description, idempotencyKey); }
    @Override public CatalogManagementModels.CategoryView updateCategory(CatalogScope scope, UUID id, UUID parentId, String slug, String name, String description, long version) { authorization.require(CatalogPermissions.MANAGE); return port.updateCategory(scope, id, parentId, slug, name, description, version); }
    @Override public CatalogManagementModels.CategoryView changeCategoryStatus(CatalogScope scope, UUID id, String status, long version) { authorization.require(CatalogPermissions.MANAGE); return port.changeCategoryStatus(scope, id, status, version); }
    @Override public CatalogManagementModels.Page<CatalogManagementModels.BrandView> brands(CatalogScope scope, int page, int size, String search) { authorization.require(CatalogPermissions.READ); return port.brands(scope, page, size, search); }
    @Override public CatalogManagementModels.BrandView brand(CatalogScope scope, UUID id) { authorization.require(CatalogPermissions.READ); return port.brand(scope, id).orElseThrow(() -> new com.nexa.api.catalogmanagement.application.exception.CatalogResourceNotFoundException("brand")); }
    @Override public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description) { authorization.require(CatalogPermissions.MANAGE); return port.createBrand(scope, slug, name, description); }
    @Override public CatalogManagementModels.BrandView createBrand(CatalogScope scope, String slug, String name, String description, String idempotencyKey) { authorization.require(CatalogPermissions.MANAGE); return port.createBrand(scope, slug, name, description, idempotencyKey); }
    @Override public CatalogManagementModels.BrandView updateBrand(CatalogScope scope, UUID id, String slug, String name, String description, long version) { authorization.require(CatalogPermissions.MANAGE); return port.updateBrand(scope, id, slug, name, description, version); }
    @Override public CatalogManagementModels.BrandView changeBrandStatus(CatalogScope scope, UUID id, String status, long version) { authorization.require(CatalogPermissions.MANAGE); return port.changeBrandStatus(scope, id, status, version); }
}
