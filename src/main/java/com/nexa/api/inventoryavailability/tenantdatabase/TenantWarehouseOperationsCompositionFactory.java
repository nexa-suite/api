package com.nexa.api.inventoryavailability.tenantdatabase;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/** Creates one request-local BC-05 application composition on the router's JDBC session. */
@FunctionalInterface
public interface TenantWarehouseOperationsCompositionFactory {
    WarehouseOperationsService bindTo(JdbcTemplate tenantJdbc, Bindings bindings);

    record Bindings(WarehouseObjectAccess verifiedWarehouseAccess,
                    Optional<WarehouseOperationalSettingsPort.Snapshot> centralSettingsSnapshot,
                    SellableSkuQuery sellableSkus,
                    InventoryCommercialSource commercialSource,
                    InventoryFulfillmentSource fulfillmentSource,
                    BusinessEvidenceQuery businessEvidence,
                    ChangeEventPersistencePort changeFeed,
                    CanonicalOutboxPort canonicalOutbox) {
        public Bindings {
            if (verifiedWarehouseAccess == null || centralSettingsSnapshot == null || sellableSkus == null
                    || commercialSource == null || fulfillmentSource == null || businessEvidence == null
                    || changeFeed == null || canonicalOutbox == null) {
                throw new IllegalArgumentException("Tenant Warehouse composition bindings are incomplete");
            }
        }
    }
}
