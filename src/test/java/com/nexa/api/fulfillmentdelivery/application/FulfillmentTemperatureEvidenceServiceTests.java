package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.fulfillmentdelivery.application.exception.FulfillmentOperationException;
import com.nexa.api.fulfillmentdelivery.application.model.FulfillmentModels;
import com.nexa.api.fulfillmentdelivery.application.port.DeliveryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.service.FulfillmentLifecycleService;
import com.nexa.api.fulfillmentdelivery.application.service.OutgoingGoodsCheckService;
import com.nexa.api.inventoryavailability.application.publicapi.ColdChainPolicyQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingQuery;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.application.publicapi.WarehouseSelectionQuery;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery.SellableSkuPolicy;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentCommands;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FulfillmentTemperatureEvidenceServiceTests {
    @Test
    void outOfRangeManualCelsiusDoesNotPersistPartialEvidence() {
        UUID tenant = UUID.randomUUID();
        UUID workspace = UUID.randomUUID();
        UUID membership = UUID.randomUUID();
        UUID fulfillmentId = UUID.randomUUID();
        UUID allocationId = UUID.randomUUID();
        UUID skuId = UUID.randomUUID();
        UUID lotId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID zoneId = UUID.randomUUID();
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        when(context.tenantId()).thenReturn(new TenantId(tenant));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspace));
        when(context.membershipId()).thenReturn(new MembershipId(membership));
        when(context.userId()).thenReturn(new UserId(UUID.randomUUID()));
        doNothing().when(context).requirePermission(PermissionKey.FULFILLMENT_MANAGE);

        FulfillmentPersistencePort fulfillments = mock(FulfillmentPersistencePort.class);
        PhysicalAllocationCommands allocations = mock(PhysicalAllocationCommands.class);
        DeliveryPersistencePort deliveries = mock(DeliveryPersistencePort.class);
        ColdChainPolicyQuery coldChain = mock(ColdChainPolicyQuery.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        PhysicalAllocationCommands.Line line = new PhysicalAllocationCommands.Line(UUID.randomUUID(), skuId,
                "CAT-001", warehouseId, zoneId, lotId, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO,
                "EA", null);
        PhysicalAllocationCommands.AllocationResult allocation = new PhysicalAllocationCommands.AllocationResult(
                allocationId, UUID.randomUUID(), "ALLOCATED", List.of(line), 3);
        when(fulfillments.find(tenant, workspace, fulfillmentId)).thenReturn(new FulfillmentModels.FulfillmentView(
                fulfillmentId, UUID.randomUUID(), allocationId, "READY_FOR_DISPATCH", null, 4,
                Instant.EPOCH, Instant.EPOCH, null, null, 0, List.of()));
        when(allocations.getByFulfillment(tenant, workspace, fulfillmentId, membership)).thenReturn(allocation);
        when(coldChain.temperatureRequirementForSku(tenant, workspace, skuId)).thenReturn(Optional.of(
                new SellableSkuPolicy(skuId, "ACTIVE", true, BigDecimal.ZERO, BigDecimal.valueOf(8))));
        when(coldChain.temperatureContextForLot(tenant, workspace, lotId)).thenReturn(Optional.of(
                new ColdChainPolicyQuery.LotTemperatureContext(lotId, warehouseId, zoneId,
                        Optional.of(new ColdChainPolicyQuery.Range(BigDecimal.ZERO, BigDecimal.valueOf(8), "CELSIUS")), skuId)));
        when(warehouseAccess.hasActiveGrant(context, warehouseId)).thenReturn(true);

        FulfillmentLifecycleService service = new FulfillmentLifecycleService(
                mock(SalesOrderFulfillmentQuery.class), mock(SalesOrderFulfillmentCommands.class),
                mock(InventoryBackingQuery.class), allocations, fulfillments, deliveries,
                mock(FinancialAdjustmentCommands.class), mock(BusinessEvidenceQuery.class),
                mock(BusinessTraceabilityCommands.class), coldChain, mock(WarehouseSelectionQuery.class),
                warehouseAccess, mock(OutgoingGoodsCheckService.class),
                Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC));

        FulfillmentOperationException error = assertThrows(FulfillmentOperationException.class,
                () -> service.recordFulfillmentTemperatureEvidence(context, fulfillmentId, 4,
                        "temp-evidence-1", new FulfillmentLifecycleService.FulfillmentTemperatureEvidenceCommand(
                                lotId, BigDecimal.valueOf(-5), "CELSIUS", Instant.parse("2026-10-01T11:59:00Z"))));

        assertEquals("TEMPERATURE_OUT_OF_RANGE_BACKEND_CONTRACT_GAP", error.code());
        verify(deliveries, never()).recordTemperatureEvidence(any());
    }
}
