package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogAvailabilityQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** Builds BC-03 request use cases from owner contracts and one router-owned Tenant JDBC session. */
@FunctionalInterface
public interface TenantCatalogCommercialPolicyRequestCompositionFactory {
    TenantCatalogCommercialPolicyRequestPort.Request bindTo(CurrentAccessContext accessContext,
            JdbcTemplate tenantJdbc, CatalogClientAccountPort tenantClientAccounts,
            CatalogAvailabilityQuery tenantAvailability,
            TenantCatalogDetailQueryFactory.TenantAvailabilityAdapterFactory availabilityFactory,
            Optional<String> regionalCurrencySnapshot);
}
