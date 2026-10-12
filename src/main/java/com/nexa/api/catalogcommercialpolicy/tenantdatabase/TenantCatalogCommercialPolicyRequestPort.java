package com.nexa.api.catalogcommercialpolicy.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemDetail;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogItemSnapshot;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogManagementModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogPricingPreviewModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogSkuModels;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogVariantModels;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingPreviewUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPricingUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogProductUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogPromotionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogSkuUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogTaxonomyUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.CatalogVariantUseCase;
import com.nexa.api.catalogcommercialpolicy.application.port.in.SkuIdentifierResolutionUseCase;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** Request-scoped BC-03 use cases bound to one verified Tenant transaction. */
public interface TenantCatalogCommercialPolicyRequestPort {
    <T> T inRequest(CurrentAccessContext accessContext, Work<T> work);

    @FunctionalInterface
    interface Work<T> {
        T execute(Request request);
    }

    interface Request {
        CatalogScope scope();

        CatalogScope readScope();

        CatalogTaxonomyUseCase taxonomy();

        CatalogProductUseCase products();

        CatalogPricingUseCase pricing();

        CatalogPricingPreviewUseCase pricingPreview();

        CatalogPromotionUseCase promotions();

        CatalogVariantUseCase variants();

        CatalogSkuUseCase skus();

        SkuIdentifierResolutionUseCase skuIdentifiers();

        CustomerOffer customerOffer(UUID clientAccountId, String catalogItemId, BigDecimal quantity);
    }

    record CustomerOffer(CatalogClientAccountPort.ClientAccountProfile client,
                         CatalogItemDetail product, Optional<CatalogItemSnapshot> quote) {
        public CustomerOffer {
            quote = quote == null ? Optional.empty() : quote;
        }
    }
}
