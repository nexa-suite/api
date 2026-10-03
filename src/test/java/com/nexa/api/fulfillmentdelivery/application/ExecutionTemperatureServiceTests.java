package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.SellableSkuPolicy;
import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.*;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.port.ExecutionTemperaturePort;
import com.nexa.api.fulfillmentdelivery.application.service.ExecutionTemperatureService;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionTemperatureServiceTests {
    private final UUID tenant=UUID.randomUUID(), workspace=UUID.randomUUID(), actor=UUID.randomUUID();
    private final UUID delivery=UUID.randomUUID(), fulfillment=UUID.randomUUID(), line=UUID.randomUUID();
    private final UUID sku=UUID.randomUUID(), warehouse=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-01T12:00:00Z");
    private final ExecutionTemperaturePort persistence=mock(ExecutionTemperaturePort.class);
    private final ColdChainPolicyQuery policy=mock(ColdChainPolicyQuery.class);
    private final BusinessEvidenceQuery evidence=mock(BusinessEvidenceQuery.class);
    private final WarehouseObjectAccess warehouses=mock(WarehouseObjectAccess.class);
    private final CurrentAccessContext context=mock(CurrentAccessContext.class);
    private final ExecutionTemperatureService service=new ExecutionTemperatureService(persistence,policy,evidence,warehouses,Clock.fixed(now,ZoneOffset.UTC));

    ExecutionTemperatureServiceTests() {
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.membershipId()).thenReturn(new MembershipId(actor));
        when(persistence.delivery(any(),eq(true),eq(true)))
            .thenReturn(new Delivery(delivery,fulfillment,warehouse,4,"IN_TRANSIT",null));
        when(persistence.lines(any(),eq(fulfillment)))
            .thenReturn(List.of(new RawLine(line,sku,"EA",BigDecimal.TEN)));
        when(policy.temperatureRequirementForSku(tenant,workspace,sku))
            .thenReturn(Optional.of(new SellableSkuPolicy(sku,"ACTIVE",true,BigDecimal.ZERO,BigDecimal.valueOf(8))));
    }

    @Test void excursionRequiresAvailablePhotoForExactIncident() {
        UUID incident=UUID.randomUUID(), photo=UUID.randomUUID();
        assertEquals("TEMPERATURE_EVIDENCE_REQUIRED", assertThrows(FulfillmentOperationException.class,()->service.record(context,delivery,4,"reading-1",command(-5,incident,photo,now))).code());
        verify(evidence).isAvailablePhotoForSubject(tenant,workspace,photo,"DELIVERY_INCIDENT",incident);
        verify(persistence,never()).record(any(),any(),anyLong(),anyString(),anyString(),any(),any(),any(),anyBoolean(),any());
    }

    @Test void excursionPreservesRealIncidentAndQuantityInAtomicCommand() {
        UUID incident=UUID.randomUUID(), photo=UUID.randomUUID();
        when(evidence.isAvailablePhotoForSubject(tenant,workspace,photo,"DELIVERY_INCIDENT",incident)).thenReturn(true);
        ReadingCommand command=command(-5,incident,photo,now);
        service.record(context,delivery,4,"reading-1",command);
        verify(context).requirePermission(PermissionKey.DOCUMENT_READ);
        verify(persistence).record(any(),any(),eq(4L),eq("reading-1"),anyString(),eq(command),eq(BigDecimal.ZERO),eq(BigDecimal.valueOf(8)),eq(true),eq(now));
    }

    @Test void exactRetryUsesStoredFactAfterTimePolicyAndStatusChange() {
        Reading stored=mock(Reading.class);
        when(persistence.delivery(any(),eq(true),eq(true)))
            .thenReturn(new Delivery(delivery,fulfillment,warehouse,8,"CANCELLED",null));
        when(persistence.replay(any(),eq("EXECUTION_TEMPERATURE"),eq("reading-1"),anyString())).thenReturn(stored);
        assertSame(stored,service.record(context,delivery,4,"reading-1",command(-5,UUID.randomUUID(),UUID.randomUUID(),now.minusSeconds(172800))));
        verifyNoInteractions(policy,evidence);
        verify(persistence,never()).lines(any(),any());
        verify(context).requirePermission(PermissionKey.DISPATCH_START_ROUTE);
    }

    @Test void dispositionRequiresExplicitCapabilityBeforePersistence() {
        doThrow(new IllegalStateException("denied")).when(context).requirePermission(PermissionKey.DELIVERY_EXECUTION_HOLD_DISPOSE);
        assertThrows(IllegalStateException.class,()->service.dispose(context,delivery,UUID.randomUUID(),4,"dispose-1",Disposition.RELEASE,"authorized assessment"));
        verifyNoInteractions(persistence,warehouses);
    }

    @Test void quantityCannotExceedRemainingPhysicalQuantity() {
        ReadingCommand command=new ReadingCommand(line,sku,BigDecimal.valueOf(11),BigDecimal.valueOf(4),"CELSIUS",now,null,null);
        assertEquals("DELIVERY_OUTCOME_EXCEEDS_REMAINING", assertThrows(FulfillmentOperationException.class,()->service.record(context,delivery,4,"reading-1",command)).code());
        verifyNoInteractions(policy,evidence);
    }

    private ReadingCommand command(int value,UUID incident,UUID photo,Instant occurredAt) {
        return new ReadingCommand(line,sku,BigDecimal.ONE,BigDecimal.valueOf(value),"CELSIUS",occurredAt,incident,photo);
    }
}
