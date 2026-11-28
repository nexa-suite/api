package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.DriverTrackingModels.DeliveryScope;
import com.nexa.api.fulfillmentdelivery.application.port.DriverTrackingPort;
import com.nexa.api.fulfillmentdelivery.application.publicapi.DispatchReadinessQueries;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.*;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudBuyerDriverLocationAuthorizationTests {
    @Test void onlyAnActiveMatchingBuyerRelationshipMayReadCoordinates() {
        var tenant=UUID.randomUUID(); var workspace=UUID.randomUUID(); var member=UUID.randomUUID();
        var delivery=UUID.randomUUID(); var order=UUID.randomUUID(); var driver=UUID.randomUUID(); var account=UUID.randomUUID();
        var now=Instant.parse("2026-11-01T12:00:00Z");
        var context=mock(CurrentAccessContext.class); var tracking=mock(DriverTrackingPort.class);
        var accounts=mock(CustomerAccountQuery.class); var orders=mock(SalesOrderFulfillmentQuery.class);
        when(context.tenantId()).thenReturn(new TenantId(tenant)); when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.membershipId()).thenReturn(new MembershipId(member)); when(context.hasRole(MembershipRole.BUYER)).thenReturn(true);
        when(tracking.deliveryScope(tenant,workspace,delivery)).thenReturn(new DeliveryScope(order,driver,"IN_TRANSIT",now));
        var snapshot=mock(SalesOrderFulfillmentQuery.Snapshot.class); when(snapshot.clientAccountId()).thenReturn(account);
        when(orders.get(tenant,workspace,order)).thenReturn(snapshot);
        var service=new DriverTrackingService(tracking,mock(WorkforceDirectory.class),accounts,orders,
                mock(DispatchReadinessQueries.class),Clock.fixed(now,ZoneOffset.UTC),true);
        when(accounts.findBuyerReference(tenant.toString(),workspace.toString(),member.toString()))
                .thenReturn(Optional.empty(), Optional.of(new CustomerAccountReference(account.toString(),"INACTIVE")),
                        Optional.of(new CustomerAccountReference(UUID.randomUUID().toString(),"ACTIVE")),
                        Optional.of(new CustomerAccountReference(account.toString(),"ACTIVE")));
        for(int i=0;i<3;i++) assertThatThrownBy(() -> service.buyerLocation(context,delivery))
                .isInstanceOf(FulfillmentOperationException.class).hasMessage("DELIVERY_NOT_FOUND");
        verify(tracking, never()).latestForDelivery(any(),any(),any(),any(),any());
        assertThat(service.buyerLocation(context,delivery).deliveryId()).isEqualTo(delivery);
        verify(tracking).latestForDelivery(tenant,workspace,driver,delivery,now);
    }
}
