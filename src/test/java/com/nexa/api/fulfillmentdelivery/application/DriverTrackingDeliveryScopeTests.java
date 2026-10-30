package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchReadinessModels.Readiness;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.Coordinate;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.DeliveryScope;
import com.nexa.api.fulfillmentdelivery.application.port.DriverTrackingPort;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchReadinessQueries;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DriverTrackingDeliveryScopeTests {
    private final UUID tenant = UUID.randomUUID(), workspace = UUID.randomUUID(), driver = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID(), fulfillment = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    private final DriverTrackingPort tracking = mock(DriverTrackingPort.class);
    private final WorkforceDirectory workforce = mock(WorkforceDirectory.class);
    private final DispatchReadinessQueries readiness = mock(DispatchReadinessQueries.class);
    private final CurrentAccessContext context = mock(CurrentAccessContext.class);

    private DriverTrackingService service() {
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.hasRole(MembershipRole.LOGISTICS)).thenReturn(true);
        when(workforce.findAssignableLogisticsName(tenant, workspace, driver)).thenReturn(Optional.of("Driver"));
        return new DriverTrackingService(tracking, workforce, mock(CustomerAccountQuery.class),
                mock(SalesOrderFulfillmentQuery.class), readiness, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void dispatchChecksWarehouseReadinessAndReadsOnlyTheRequestedActiveDelivery() {
        DriverTrackingService service = service();
        when(tracking.deliveryScope(tenant, workspace, delivery)).thenReturn(
                new DeliveryScope(UUID.randomUUID(), driver, "IN_TRANSIT", now.minusSeconds(60), fulfillment));
        Readiness current = mock(Readiness.class);
        when(current.deliveryId()).thenReturn(delivery);
        when(current.deliveryStatus()).thenReturn("IN_TRANSIT");
        when(readiness.readiness(context, fulfillment)).thenReturn(current);
        Coordinate location = new Coordinate(UUID.randomUUID(), -12, -77, 5, now, now.plusSeconds(120));
        when(tracking.latestForDelivery(tenant, workspace, driver, delivery, now)).thenReturn(location);

        assertThat(service.dispatchLocation(context, driver, delivery)).isSameAs(location);
        verify(readiness).readiness(context, fulfillment);
        verify(tracking).latestForDelivery(tenant, workspace, driver, delivery, now);
        verify(tracking, never()).latest(any(), any(), any(), any());
    }

    @Test
    void anotherDriverOrCompletedDeliveryCannotReadCoordinates() {
        DriverTrackingService service = service();
        when(tracking.deliveryScope(tenant, workspace, delivery)).thenReturn(
                new DeliveryScope(UUID.randomUUID(), UUID.randomUUID(), "IN_TRANSIT", now, fulfillment));
        assertThatThrownBy(() -> service.dispatchLocation(context, driver, delivery))
                .isInstanceOf(FulfillmentOperationException.class);
        when(tracking.deliveryScope(tenant, workspace, delivery)).thenReturn(
                new DeliveryScope(UUID.randomUUID(), driver, "COMPLETED", now, fulfillment));
        assertThatThrownBy(() -> service.dispatchLocation(context, driver, delivery))
                .isInstanceOf(FulfillmentOperationException.class);
        verifyNoInteractions(readiness);
        verify(tracking, never()).latestForDelivery(any(), any(), any(), any(), any());
    }
}
