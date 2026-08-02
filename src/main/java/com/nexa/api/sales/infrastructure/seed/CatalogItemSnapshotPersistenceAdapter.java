package com.nexa.api.sales.infrastructure.seed;

import com.nexa.api.catalogmanagement.application.port.in.GetCatalogItemSnapshotUseCase;
import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PriceSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("!test")
public class CatalogItemSnapshotPersistenceAdapter implements CatalogItemSnapshotLookupPort {
	private final GetCatalogItemSnapshotUseCase catalog;
	public CatalogItemSnapshotPersistenceAdapter(GetCatalogItemSnapshotUseCase catalog) { this.catalog = catalog; }
	@Override public Optional<CatalogItemSnapshot> findActive(String catalogItemId) { return Optional.empty(); }
	@Override public Optional<CatalogItemSnapshot> findActive(String catalogItemId, java.util.UUID tenantId, java.util.UUID workspaceId) {
		return catalog.findActive(catalogItemId, tenantId, workspaceId).map(item -> new CatalogItemSnapshot(item.catalogItemId(), item.itemName(), item.presentation(), new PriceSnapshot(item.unitPriceAmount(), item.unitPriceCurrency())));
	}
}
