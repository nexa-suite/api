package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner.Requirements;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.junit.jupiter.api.Test;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.DeliveryTracking;
import java.util.UUID;
import java.util.Optional;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TenantBoundFulfillmentScopedLocationTests {
    @Test
    void scopedCloudRouteAllowsBuyerButDoesNotEnableGeneralCoordinateReads() {
        var router = mock(TenantBusinessDatabaseRouter.class);
        var compositions = mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class);
        var warehouses = mock(WarehouseObjectAccess.class);
        var workforce = mock(WorkforceDirectory.class);
        var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router, compositions, warehouses, workforce,
                false, true);
        var context = mock(CurrentAccessContext.class);

        when(router.inReadOnlyTransaction(eq(context), any())).thenReturn(new DeliveryTracking(UUID.randomUUID(), null));
        runner.execute(context, Requirements.buyerDeliveryLocation(), composition -> null);
        verify(router).inReadOnlyTransaction(eq(context), any());
        clearInvocations(router);
        assertThatThrownBy(() -> runner.execute(context, Requirements.requiringStoredDriverLocation(),
                composition -> null)).isInstanceOf(FulfillmentOperationException.class)
                .hasMessage("DRIVER_LOCATION_UNAVAILABLE");
        assertThatThrownBy(() -> runner.execute(context, Requirements.driver().withStoredDriverLocation(),
                composition -> null)).isInstanceOf(FulfillmentOperationException.class)
                .hasMessage("DRIVER_LOCATION_UNAVAILABLE");
        verifyNoInteractions(router, compositions, warehouses, workforce);
    }
    @Test
    void cloudCaptureRoutesOnlyAfterDriverPreflight() {
        var router = mock(TenantBusinessDatabaseRouter.class);
        var workforce = mock(WorkforceDirectory.class);
        var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router,
                mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class), mock(WarehouseObjectAccess.class),
                workforce, false, true);
        var context = mock(CurrentAccessContext.class);
        var tenant = UUID.randomUUID();
        var workspace = UUID.randomUUID();
        var driver = UUID.randomUUID();
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.membershipId()).thenReturn(new MembershipId(driver));
        when(workforce.findAssignableLogisticsName(tenant, workspace, driver)).thenReturn(Optional.of("Driver"));
        runner.execute(context, Requirements.driverLocationCapture(), c -> null);
        verify(workforce).findAssignableLogisticsName(tenant, workspace, driver);
        verify(router).inTransaction(eq(context), any());
    }
    @Test
    void enabledCloudLocationRequiresScheduler() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("nexa.scheduling.enabled", "false");
        assertThatThrownBy(() -> new CloudMobileFulfillmentConfiguration().cloudMobileFulfillmentRoute(
                mock(TenantBusinessDatabaseRouter.class), mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class),
                mock(WarehouseObjectAccess.class), mock(WorkforceDirectory.class), true, env))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("scheduled memory expiration");
    }
}
