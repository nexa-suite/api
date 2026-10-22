package com.nexa.api.fulfillmentdelivery.application;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.model.DispatchSalesOwnerScope;
import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.HandoffNoteView;
import com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.DispatchView;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchCommandPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchQueryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalHandoffPort;
import com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LogisticsOperationsServiceTests {
    private final UUID tenantId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID actorMembershipId = UUID.randomUUID();
    private final UUID dispatchId = UUID.randomUUID();
    private final UUID warehouseA = UUID.randomUUID();
    private final UUID warehouseB = UUID.randomUUID();
    private final long expectedVersion = 7L;
    private final String idempotencyKey = "handoff-note-1";
    private final String note = "Carga lista para transferencia";

    @Test
    void warehousePassesActiveWarehouseGrantsToHandoffWrite() {
        OperationalHandoffPort handoff = mock(OperationalHandoffPort.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        CurrentAccessContext context = context(true, false);
        Set<UUID> grants = Set.of(warehouseA, warehouseB);
        HandoffNoteView created = handoffNote();
        when(warehouseAccess.activeWarehouseIds(context)).thenReturn(grants);
        when(handoff.append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(grants), anyLong())).thenReturn(created);

        HandoffNoteView result = service(handoff, warehouseAccess).appendHandoffNote(
                context, dispatchId.toString(), expectedVersion, idempotencyKey, note);

        assertSame(created, result);
        verify(context).requirePermission(Permission.WAREHOUSE_WRITE);
        verify(warehouseAccess).activeWarehouseIds(context);
        verify(handoff).append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(grants), anyLong());
    }

    @Test
    void warehouseWithoutGrantAuthorityPassesEmptyGrantSet() {
        OperationalHandoffPort handoff = mock(OperationalHandoffPort.class);
        CurrentAccessContext context = context(true, false);
        HandoffNoteView created = handoffNote();
        when(handoff.append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(Set.of()), anyLong())).thenReturn(created);

        HandoffNoteView result = service(handoff, null).appendHandoffNote(
                context, dispatchId.toString(), expectedVersion, idempotencyKey, note);

        assertSame(created, result);
        verify(context).requirePermission(Permission.WAREHOUSE_WRITE);
        verify(handoff).append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(Set.of()), anyLong());
    }

    @Test
    void logisticsWriteKeepsTenantWorkspaceScopeWithoutReadingWarehouseGrants() {
        OperationalHandoffPort handoff = mock(OperationalHandoffPort.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        CurrentAccessContext context = context(false, true);
        HandoffNoteView created = handoffNote();
        when(handoff.append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(null), anyLong())).thenReturn(created);

        HandoffNoteView result = service(handoff, warehouseAccess).appendHandoffNote(
                context, dispatchId.toString(), expectedVersion, idempotencyKey, note);

        assertSame(created, result);
        verify(context).requirePermission(Permission.LOGISTICS_WRITE);
        verifyNoInteractions(warehouseAccess);
        verify(handoff).append(eq(tenantId.toString()), eq(workspaceId.toString()), eq(dispatchId.toString()),
                eq(expectedVersion), eq(actorMembershipId.toString()), eq(idempotencyKey), eq(note),
                eq(null), anyLong());
    }

    @Test
    void logisticsReadUsesTenantWorkspaceDispatchReadWithoutWarehouseGrantLookup() {
        DispatchQueryPersistencePort queries = mock(DispatchQueryPersistencePort.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        CurrentAccessContext context = context(false, false);
        when(context.allows(Permission.LOGISTICS_READ)).thenReturn(true);
        DispatchView dispatch = dispatchView();
        when(queries.detail(tenantId.toString(), workspaceId.toString(), null, dispatchId.toString()))
                .thenReturn(dispatch);

        DispatchView result = service(queries, null, warehouseAccess).detail(context, dispatchId.toString());

        assertSame(dispatch, result);
        verifyNoInteractions(warehouseAccess);
        verify(queries).detail(tenantId.toString(), workspaceId.toString(), null, dispatchId.toString());
    }

    @Test
    void fulfillmentReadPassesEmptyWarehouseGrantsWhenNoWarehouseIsAuthorized() {
        DispatchQueryPersistencePort queries = mock(DispatchQueryPersistencePort.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        CurrentAccessContext context = context(false, false);
        when(context.allows(Permission.FULFILLMENT_READ)).thenReturn(true);
        when(warehouseAccess.activeWarehouseIds(context)).thenReturn(Set.of());
        when(queries.detail(tenantId.toString(), workspaceId.toString(), null, dispatchId.toString(), Set.of()))
                .thenReturn(dispatchView());

        DispatchView result = service(queries, null, warehouseAccess).detail(context, dispatchId.toString());

        assertNull(result.salesOrderId());
        verify(warehouseAccess).activeWarehouseIds(context);
        verify(queries).detail(tenantId.toString(), workspaceId.toString(), null, dispatchId.toString(), Set.of());
    }

    @Test
    void salesReadUsesOnlyTheAccountsAssignedToItsMembershipForListDetailAndEvents() {
        DispatchQueryPersistencePort queries = mock(DispatchQueryPersistencePort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        WarehouseObjectAccess warehouseAccess = mock(WarehouseObjectAccess.class);
        CurrentAccessContext context = context(false, false);
        when(context.hasRole(MembershipRole.SALES)).thenReturn(true);
        when(context.allows(PermissionKey.SALES_ORDER_READ)).thenReturn(true);
        String accountId = UUID.randomUUID().toString();
        DispatchSalesOwnerScope scope = new DispatchSalesOwnerScope(Set.of(UUID.fromString(accountId)));
        when(accounts.lockActiveSalesOwnerAccountIds(tenantId.toString(), workspaceId.toString(),
                actorMembershipId.toString())).thenReturn(List.of(accountId));
        DispatchView dispatch = dispatchView();
        when(queries.listForSalesOwner(tenantId.toString(), workspaceId.toString(), null, 0, 25, "updatedAt,desc",
                scope)).thenReturn(new com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.Page<>(
                List.of(dispatch), 0, 25, 1));
        when(queries.detailForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(), scope))
                .thenReturn(dispatch);
        var event = new com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.DispatchEventView(
                "event", "logistics.dispatch.scheduled", "ASSIGNED", "SCHEDULED", "2026-10-22T12:00:00Z",
                false, "SCHEDULED");
        when(queries.eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(), scope))
                .thenReturn(List.of(event));
        LogisticsOperationsService service = new LogisticsOperationsService(queries,
                mock(DispatchCommandPersistencePort.class), accounts, mock(StartDispatchRouteService.class), null,
                warehouseAccess);

        var page = service.list(context, null, 0, 25, "updatedAt,desc");
        DispatchView detail = service.detail(context, dispatchId.toString());
        var events = service.events(context, dispatchId.toString());

        assertEquals(1, page.total());
        assertEquals("dispatch", detail.id());
        assertEquals(1, events.size());
        verify(accounts, org.mockito.Mockito.times(3)).lockActiveSalesOwnerAccountIds(tenantId.toString(),
                workspaceId.toString(), actorMembershipId.toString());
        verify(queries).listForSalesOwner(tenantId.toString(), workspaceId.toString(), null, 0, 25,
                "updatedAt,desc", scope);
        verify(queries).detailForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(), scope);
        verify(queries).eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(), scope);
        verifyNoInteractions(warehouseAccess);
    }

    @Test
    void salesWithoutAssignedActiveAccountsGetsEmptyListAndCannotReadDetails() {
        DispatchQueryPersistencePort queries = mock(DispatchQueryPersistencePort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        CurrentAccessContext context = context(false, false);
        when(context.hasRole(MembershipRole.SALES)).thenReturn(true);
        when(context.allows(PermissionKey.SALES_ORDER_READ)).thenReturn(true);
        DispatchSalesOwnerScope emptyScope = new DispatchSalesOwnerScope(Set.of());
        when(accounts.lockActiveSalesOwnerAccountIds(tenantId.toString(), workspaceId.toString(),
                actorMembershipId.toString())).thenReturn(List.of());
        when(queries.listForSalesOwner(tenantId.toString(), workspaceId.toString(), null, 0, 25, "updatedAt,desc",
                emptyScope)).thenReturn(new com.nexa.api.fulfillmentdelivery.application.model.LogisticsOperationsModels.Page<>(
                List.of(), 0, 25, 0));
        when(queries.detailForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(), emptyScope))
                .thenThrow(new LogisticsOperationsService.LogisticsException("RESOURCE_NOT_FOUND", true));
        LogisticsOperationsService service = new LogisticsOperationsService(queries,
                mock(DispatchCommandPersistencePort.class), accounts, mock(StartDispatchRouteService.class));

        var page = service.list(context, null, 0, 25, "updatedAt,desc");

        assertEquals(0, page.total());
        assertThrows(LogisticsOperationsService.LogisticsException.class,
                () -> service.detail(context, dispatchId.toString()));
        verify(queries).listForSalesOwner(tenantId.toString(), workspaceId.toString(), null, 0, 25,
                "updatedAt,desc", emptyScope);
        verify(queries).detailForSalesOwner(tenantId.toString(), workspaceId.toString(), dispatchId.toString(),
                emptyScope);
    }

    private CurrentAccessContext context(boolean warehouseWrite, boolean logisticsWrite) {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        when(context.tenantId()).thenReturn(new TenantId(tenantId));
        when(context.workspaceId()).thenReturn(new WorkspaceId(workspaceId));
        when(context.membershipId()).thenReturn(new MembershipId(actorMembershipId));
        when(context.allows(Permission.WAREHOUSE_WRITE)).thenReturn(warehouseWrite);
        when(context.allows(Permission.LOGISTICS_WRITE)).thenReturn(logisticsWrite);
        return context;
    }

    private LogisticsOperationsService service(OperationalHandoffPort handoff,
                                                WarehouseObjectAccess warehouseAccess) {
        return service(mock(DispatchQueryPersistencePort.class), handoff, warehouseAccess);
    }

    private LogisticsOperationsService service(DispatchQueryPersistencePort queries,
                                                OperationalHandoffPort handoff,
                                                WarehouseObjectAccess warehouseAccess) {
        return new LogisticsOperationsService(queries,
                mock(DispatchCommandPersistencePort.class), mock(CustomerAccountQuery.class),
                mock(StartDispatchRouteService.class), handoff, warehouseAccess);
    }

    private HandoffNoteView handoffNote() {
        return new HandoffNoteView(UUID.randomUUID().toString(), dispatchId.toString(), note,
                actorMembershipId.toString(), Instant.parse("2026-10-22T12:00:00Z"), expectedVersion + 1);
    }

    private DispatchView dispatchView() {
        return new DispatchView("dispatch", "D-1", "reservation", "sales-order", "SO-1", "client-account",
                "C-1", "Client", "IN_ROUTE", "destination", "area", "NORMAL", null, null, null,
                null, null, null, null, "OK", null, null, 1, Instant.parse("2026-10-22T12:00:00Z"),
                List.of(), null, null, null, List.of());
    }
}
