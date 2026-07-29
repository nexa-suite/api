package com.nexa.api.catalogmanagement.application.service;

import com.nexa.api.catalogmanagement.application.exception.CatalogItemNotFoundException;
import com.nexa.api.catalogmanagement.application.model.CatalogItemDetail;
import com.nexa.api.catalogmanagement.application.model.CatalogPage;
import com.nexa.api.catalogmanagement.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogmanagement.application.port.in.GetCatalogItemUseCase;
import com.nexa.api.catalogmanagement.application.port.in.ListCatalogItemsUseCase;
import com.nexa.api.catalogmanagement.application.port.out.CatalogItemQueryPort;
import com.nexa.api.catalogmanagement.application.port.out.CatalogAuthorizationPort;
import com.nexa.api.catalogmanagement.domain.model.catalogitem.CatalogItemId;

import java.util.Objects;

public final class CatalogQueryService implements ListCatalogItemsUseCase, GetCatalogItemUseCase {
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
	public CatalogPage<com.nexa.api.catalogmanagement.application.model.CatalogItemSummary> list(CatalogSearchCriteria criteria) {
		CatalogSearchCriteria safeCriteria = Objects.requireNonNull(criteria, "Catalog search criteria is required");
		authorization.requireCatalogRead();
		return queryPort.search(safeCriteria);
	}

	@Override
	public CatalogItemDetail getByCatalogItemId(String catalogItemId) {
		CatalogItemId id = new CatalogItemId(catalogItemId);
		authorization.requireCatalogRead();
		return queryPort.findByCatalogItemId(id)
				.orElseThrow(() -> new CatalogItemNotFoundException(id.value()));
	}
}
