package com.nexa.api.catalogcommercialpolicy.application.service;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogItemNotFoundException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSnapshot;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.ListCatalogItemsUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogcommercialpolicy.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogcommercialpolicy.domain.model.catalogitem.CatalogItemId;

import java.util.Objects;

public final class CatalogQueryService implements ListCatalogItemsUseCase, GetCatalogItemUseCase, GetCatalogItemSnapshotUseCase {
	private final CatalogItemQueryPort queryPort;
	private final CatalogAuthorizationPort authorization;

	public CatalogQueryService(CatalogItemQueryPort queryPort) {
		this(queryPort, () -> { });
	}

	public CatalogQueryService(CatalogItemQueryPort queryPort, CatalogAuthorizationPort authorization) {
		this.queryPort = Objects.requireNonNull(queryPort, "Catalog item query port is required");
		this.authorization = Objects.requireNonNull(authorization, "Catalog authorization port is required");
	}

	@Override
	public CatalogPage<com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary> list(CatalogSearchCriteria criteria) {
		CatalogSearchCriteria safeCriteria = Objects.requireNonNull(criteria, "Catalog search criteria is required");
		authorization.requireCatalogRead();
		return queryPort.search(safeCriteria);
	}

	@Override
	public CatalogPage<com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary> list(CatalogScope scope, CatalogSearchCriteria criteria) {
		Objects.requireNonNull(scope, "Catalog scope is required");
		CatalogSearchCriteria safeCriteria = Objects.requireNonNull(criteria, "Catalog search criteria is required");
		authorization.requireCatalogRead();
		return queryPort.search(scope, safeCriteria);
	}

	@Override
	public CatalogItemDetail getByCatalogItemId(String catalogItemId) {
		CatalogItemId id = new CatalogItemId(catalogItemId);
		authorization.requireCatalogRead();
		return queryPort.findByCatalogItemId(id)
				.orElseThrow(() -> new CatalogItemNotFoundException(id.value()));
	}

	@Override
	public CatalogItemDetail getByCatalogItemId(CatalogScope scope, String catalogItemId) {
		CatalogItemId id = new CatalogItemId(catalogItemId);
		authorization.requireCatalogRead();
		return queryPort.findByCatalogItemId(scope, id)
				.orElseThrow(() -> new CatalogItemNotFoundException(id.value()));
	}

	@Override
	public java.util.Optional<CatalogItemSnapshot> findActive(String catalogItemId, java.util.UUID tenantId, java.util.UUID workspaceId) {
		CatalogItemDetail detail = queryPort.findByCatalogItemId(new CatalogScope(tenantId, workspaceId), new CatalogItemId(catalogItemId)).orElse(null);
		return detail == null ? java.util.Optional.empty() : java.util.Optional.of(new CatalogItemSnapshot(
				 detail.catalogItemId(), detail.itemName(), detail.presentation(), detail.unitPriceAmount(), detail.unitPriceCurrency()));
	}

	@Override
	public java.util.List<CatalogItemSnapshot> findActive(java.util.List<String> catalogItemIds, java.util.UUID tenantId, java.util.UUID workspaceId) {
		if (catalogItemIds == null || catalogItemIds.isEmpty()) return java.util.List.of();
		return queryPort.findByCatalogItemIds(new com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope(tenantId, workspaceId),
				catalogItemIds.stream().filter(id -> id != null && !id.isBlank()).distinct().map(CatalogItemId::new).toList()).stream()
				.map(detail -> new CatalogItemSnapshot(detail.catalogItemId(), detail.itemName(), detail.presentation(), detail.unitPriceAmount(), detail.unitPriceCurrency()))
				.toList();
	}
}
