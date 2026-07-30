package com.nexa.api.sales.infrastructure.seed;

import com.nexa.api.catalogmanagement.infrastructure.seed.CatalogSeedLoader;
import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PriceSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("!test")
public class CatalogItemSnapshotPersistenceAdapter implements CatalogItemSnapshotLookupPort {
	private final CatalogSeedLoader catalog;
	public CatalogItemSnapshotPersistenceAdapter(CatalogSeedLoader catalog) { this.catalog = catalog; }
	@Override public Optional<CatalogItemSnapshot> findActive(String catalogItemId) { return catalog.load().stream().filter(item -> item.catalogItemId().equals(catalogItemId)).findFirst().map(item -> new CatalogItemSnapshot(item.catalogItemId(), item.itemName(), item.presentation(), new PriceSnapshot(item.unitPriceAmount(), item.unitPriceCurrency()))); }
}
