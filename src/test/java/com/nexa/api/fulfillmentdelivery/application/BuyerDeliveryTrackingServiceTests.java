package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.DeliveryRecord;
import com.nexa.api.fulfillmentdelivery.application.port.BuyerDeliveryTrackingQueryPort;
import com.nexa.api.fulfillmentdelivery.application.service.BuyerDeliveryTrackingService;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerDeliveryTrackingServiceTests {
    private final UUID tenant = UUID.randomUUID();
    private final UUID workspace = UUID.randomUUID();
    private final UUID membership = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();
    private final UUID otherAccount = UUID.randomUUID();
    private final UUID deliveryId = UUID.randomUUID();
    private final UUID salesOrderId = UUID.randomUUID();
    private final CurrentAccessContext context = mock(CurrentAccessContext.class);
    private final BuyerDeliveryTrackingQueryPort deliveries = mock(BuyerDeliveryTrackingQueryPort.class);
    private final CustomerAccountQuery customerAccounts = mock(CustomerAccountQuery.class);
    private final SalesOrderFulfillmentQuery salesOrders = mock(SalesOrderFulfillmentQuery.class);
    private final BuyerDeliveryTrackingService service = new BuyerDeliveryTrackingService(
            deliveries, customerAccounts, salesOrders);

    BuyerDeliveryTrackingServiceTests() {
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.membershipId()).thenReturn(new MembershipId(membership));
        when(customerAccounts.findBuyerReference(tenant.toString(), workspace.toString(), membership.toString()))
                .thenReturn(Optional.of(new CustomerAccountReference(account.toString(), "ACTIVE")));
    }

    @Test
    void detailValidatesDeliveryOrderAgainstCurrentBuyerAccount() {
        when(deliveries.detail(tenant, workspace, deliveryId)).thenReturn(delivery());
        when(salesOrders.findHeaders(tenant, workspace, List.of(salesOrderId)))
                .thenReturn(Map.of(salesOrderId, new SalesOrderFulfillmentQuery.Header(
                        salesOrderId, "SO-2042", otherAccount, "NORMAL")));

        assertNotFound(() -> service.detail(context, deliveryId));

        verify(salesOrders).findHeaders(tenant, workspace, List.of(salesOrderId));
        verify(deliveries, never()).events(tenant, workspace, deliveryId);
    }

    @Test
    void detailFailsClosedWhenSalesOwnerCannotResolveOrderInsideCurrentTenantAndWorkspace() {
        when(deliveries.detail(tenant, workspace, deliveryId)).thenReturn(delivery());
        when(salesOrders.findHeaders(tenant, workspace, List.of(salesOrderId))).thenReturn(Map.of());

        assertNotFound(() -> service.detail(context, deliveryId));

        verify(deliveries).detail(tenant, workspace, deliveryId);
        verify(salesOrders).findHeaders(tenant, workspace, List.of(salesOrderId));
    }

    @Test
    void missingBuyerTrackingGrantFailsBeforeAnyScopeOrDeliveryLookup() {
        doThrow(new IllegalStateException("permission denied"))
                .when(context).requirePermission(PermissionKey.BUYER_TRACKING_READ);

        assertThatThrownBy(() -> service.detail(context, deliveryId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("permission denied");

        verifyNoInteractions(customerAccounts, deliveries, salesOrders);
    }

    @Test
    void authorizedBuyerReceivesOnlySafeDeliveryProjectionAndTimelineFacts() {
        Instant handedOverAt = Instant.parse("2026-10-09T15:08:12Z");
        when(deliveries.detail(tenant, workspace, deliveryId)).thenReturn(delivery());
        when(salesOrders.findHeaders(tenant, workspace, List.of(salesOrderId)))
                .thenReturn(Map.of(salesOrderId, new SalesOrderFulfillmentQuery.Header(
                        salesOrderId, "SO-2042", account, "NORMAL")));
        when(deliveries.events(tenant, workspace, deliveryId)).thenReturn(
                List.of(new com.nexa.api.fulfillmentdelivery.application.model.BuyerDeliveryTrackingModels.EventView(
                        "HANDED_OVER", handedOverAt)));

        var detail = service.detail(context, deliveryId);
        var events = service.events(context, deliveryId);

        assertThat(detail.id()).isEqualTo(deliveryId.toString());
        assertThat(detail.salesOrderNumber()).isEqualTo("SO-2042");
        assertThat(detail.status()).isEqualTo("DISPATCHED");
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("HANDED_OVER");
        assertThat(events.getFirst().occurredAt()).isEqualTo(handedOverAt);
        verify(context, org.mockito.Mockito.times(2)).requirePermission(PermissionKey.BUYER_TRACKING_READ);
    }

    private DeliveryRecord delivery() {
        Instant createdAt = Instant.parse("2026-10-09T15:08:12Z");
        return new DeliveryRecord(deliveryId, salesOrderId, null, "DISPATCHED", "Buyer destination",
                null, createdAt, null, null, 0, createdAt, createdAt);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(FulfillmentOperationException.class)
                .satisfies(exception -> assertThat(((FulfillmentOperationException) exception).code())
                        .isEqualTo("RESOURCE_NOT_FOUND"));
    }
}
