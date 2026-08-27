package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.CatalogPermissions;
import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogResourceNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogProductPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemStatus;
import com.nexa.api.catalogcommercialpolicy.domain.model.product.Product;

import java.util.Objects;
import java.util.UUID;

public final class CatalogProductService implements CatalogProductUseCase {
	private final CatalogProductPort port;
	private final CatalogAuthorizationPort authorization;

	public CatalogProductService(CatalogProductPort port, CatalogAuthorizationPort authorization) {
		this.port = Objects.requireNonNull(port, "Catalog product port is required");
		this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
	}

	@Override
	public CatalogManagementModels.Page<CatalogManagementModels.ProductView> products(CatalogScope scope, int page, int size, String search, String status) {
		authorization.require(CatalogPermissions.READ);
		return port.products(scope, page, size, search, status);
	}

	@Override
	public CatalogManagementModels.ProductView product(CatalogScope scope, UUID id) {
		authorization.require(CatalogPermissions.READ);
		return port.product(scope, id).orElseThrow(() -> new CatalogResourceNotFoundException("product"));
	}

	@Override
	public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode,
			String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature,
			String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath) {
		return createProduct(scope, catalogItemId, productCode, slug, name, description, categoryId, brandId,
				storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath, null);
	}

	@Override
	public CatalogManagementModels.ProductView createProduct(CatalogScope scope, String catalogItemId, String productCode,
			String slug, String name, String description, UUID categoryId, UUID brandId, String storageTemperature,
			String presentation, String unitOfMeasure, boolean buyerVisible, String imagePath, String idempotencyKey) {
		authorization.require(CatalogPermissions.MANAGE);
		Product product = Product.create(UUID.randomUUID(), catalogItemId, productCode, slug, name, description);
		return port.createProduct(scope, product.catalogItemId(), product.productCode(), product.slug(), product.name(), product.description(),
				categoryId, brandId, storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath, idempotencyKey);
	}

	@Override
	public CatalogManagementModels.ProductView updateProduct(CatalogScope scope, UUID id, String slug, String name, String description,
			UUID categoryId, UUID brandId, String storageTemperature, String presentation, String unitOfMeasure,
			boolean buyerVisible, String imagePath, long version) {
		authorization.require(CatalogPermissions.MANAGE);
		CatalogManagementModels.ProductView current = port.product(scope, id)
				.orElseThrow(() -> new CatalogResourceNotFoundException("product"));
		Product product = Product.restore(id, current.catalogItemId(), current.productCode(), current.slug(), current.name(),
				current.description(), CatalogItemStatus.valueOf(current.status()));
		product.changeSlug(slug);
		product.rename(name);
		product.rewriteDescription(description);
		return port.updateProduct(scope, id, product.slug(), product.name(), product.description(), categoryId, brandId,
				storageTemperature, presentation, unitOfMeasure, buyerVisible, imagePath, version);
	}

	@Override
	public CatalogManagementModels.ProductView changeStatus(CatalogScope scope, UUID id, String status, long version) {
		authorization.require(CatalogPermissions.MANAGE);
		CatalogManagementModels.ProductView current = port.product(scope, id)
				.orElseThrow(() -> new CatalogResourceNotFoundException("product"));
		Product product = Product.restore(id, current.catalogItemId(), current.productCode(), current.slug(), current.name(),
				current.description(), CatalogItemStatus.valueOf(current.status()));
		CatalogItemStatus target = CatalogItemStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT));
		if (target != product.status()) {
			switch (target) {
				case ACTIVE -> product.activate();
				case INACTIVE -> product.deactivate();
				case DISCONTINUED -> product.discontinue();
				case ARCHIVED -> product.archive();
				case DRAFT -> throw new IllegalStateException("Product cannot return to DRAFT");
			}
		}
		return port.changeStatus(scope, id, target.name(), version);
	}
}
