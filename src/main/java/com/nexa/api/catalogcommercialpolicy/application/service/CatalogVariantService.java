package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSkuModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogVariantModels;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogVariantUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogVariantPort;

import java.util.Objects;
import java.util.UUID;

public final class CatalogVariantService implements CatalogVariantUseCase {
    private final CatalogVariantPort port;
    private final CatalogAuthorizationPort authorization;

    public CatalogVariantService(CatalogVariantPort port, CatalogAuthorizationPort authorization) {
        this.port = Objects.requireNonNull(port, "Catalog variant port is required");
        this.authorization = Objects.requireNonNull(authorization, "Catalog authorization is required");
    }

    @Override
    public CatalogVariantModels.Page<CatalogVariantModels.VariantView> variants(CatalogScope scope, UUID familyId, int page, int size, String search) {
        authorization.require(CatalogPermissions.READ);
        return port.variants(scope, familyId, page, size, search);
    }

    @Override
    public CatalogVariantModels.VariantView variant(CatalogScope scope, UUID id) {
        authorization.require(CatalogPermissions.READ);
        return port.variant(scope, id);
    }

    @Override
    public CatalogVariantModels.VariantView create(CatalogScope scope, UUID familyId, String code, String name, String description) {
        authorization.require(CatalogPermissions.MANAGE);
        return port.insert(scope, familyId, code, name, description);
    }

    @Override
    public CatalogSkuModels.Page<CatalogSkuModels.SkuView> skus(CatalogScope scope, UUID variantId, int page, int size, String search) {
        authorization.require(CatalogPermissions.READ);
        return port.skus(scope, variantId, page, size, search);
    }
}
