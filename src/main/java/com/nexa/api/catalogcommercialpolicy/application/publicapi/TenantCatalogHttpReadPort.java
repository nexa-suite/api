package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPage;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSearchCriteria;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSummary;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

/** Explicit Tenant-bound Catalog HTTP read path; it never falls back to central business storage. */
@org.springframework.modulith.NamedInterface("catalog-tenant-read")
public interface TenantCatalogHttpReadPort {
	CatalogPage<CatalogItemSummary> list(CurrentAccessContext accessContext, CatalogSearchCriteria criteria);

	CatalogItemDetail getByCatalogItemId(CurrentAccessContext accessContext, String catalogItemId);
}
