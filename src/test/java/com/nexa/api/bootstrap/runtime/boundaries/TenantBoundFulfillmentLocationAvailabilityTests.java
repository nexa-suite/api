package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TenantBoundFulfillmentLocationAvailabilityTests {
    @Test
    void unsupportedStoredLocationCannotFallBackToCentralBusinessPersistence() {
        var router = mock(TenantBusinessDatabaseRouter.class);
        var compositions = mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class);
        var warehouses = mock(WarehouseObjectAccess.class);
        var workforce = mock(WorkforceDirectory.class);
        var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions, warehouses, workforce);

        assertThatThrownBy(() -> runner.execute(mock(CurrentAccessContext.class),
                FulfillmentDeliveryRequestRunner.Requirements.requiringStoredDriverLocation(),
                composition -> { throw new AssertionError("Unavailable location must not execute business work"); }))
                .isInstanceOf(FulfillmentOperationException.class)
                .hasMessage("DRIVER_LOCATION_UNAVAILABLE");
        verifyNoInteractions(router, compositions, warehouses, workforce);
    }
}
