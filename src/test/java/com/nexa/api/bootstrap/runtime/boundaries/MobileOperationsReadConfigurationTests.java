package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessEvidenceQueryFactory;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantSellableSkuQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeEventPersistenceFactory;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.port.PickingWorkRead;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentInventoryQueryFactory;
import com.nexa.api.inventoryavailability.application.port.InventoryLotsRead;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationsRequestRunner;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantWarehouseOperationsCompositionFactory;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesOrderFulfillmentQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.OperationalSettingsAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MobileOperationsReadConfigurationTests {
    @Test
    void cloudReadsRequireExplicitOptInAndLeaveGeneralRunnersUntouched() {
        context().run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean("cloudInventoryLotsRead")
                    .doesNotHaveBean("cloudPickingWorkRead");
        });
        context().withPropertyValues("nexa.tenant-business.cloud-operations-read.enabled=true").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasBean("cloudInventoryLotsRead").hasBean("cloudPickingWorkRead");
            CurrentAccessContext scope = mock(CurrentAccessContext.class);
            when(scope.workspaceId()).thenReturn(WorkspaceId.random());
            var access = ctx.getBean(WarehouseObjectAccess.class);
            when(access.activeWarehouseIds(scope)).thenReturn(Set.of());
            when(ctx.getBean(OperationalSettingsAccess.class).find(any())).thenReturn(Optional.empty());
            ctx.getBean(InventoryLotsRead.class).list(scope, null, null, null, null, 0, 25, "expirationDate,asc");
            ctx.getBean(PickingWorkRead.class).list(scope, 0, 25);
            verify(ctx.getBean(TenantBusinessDatabaseRouter.class), times(2)).inReadOnlyTransaction(any(), any());
            verifyNoInteractions(ctx.getBean(WarehouseOperationsRequestRunner.class),
                    ctx.getBean(FulfillmentDeliveryRequestRunner.class));
        });
    }

    @Test
    void unavailableBindingOrSchemaNeverFallsBackToCentralBusinessData() {
        for (RuntimeException failure : java.util.List.of(
                new com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException("unavailable"),
                new com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException())) {
            context().withPropertyValues("nexa.tenant-business.cloud-operations-read.enabled=true").run(ctx -> {
                CurrentAccessContext scope = mock(CurrentAccessContext.class);
                when(scope.workspaceId()).thenReturn(WorkspaceId.random());
                when(ctx.getBean(WarehouseObjectAccess.class).activeWarehouseIds(scope)).thenReturn(Set.of());
                when(ctx.getBean(OperationalSettingsAccess.class).find(any())).thenReturn(Optional.empty());
                when(ctx.getBean(TenantBusinessDatabaseRouter.class).inReadOnlyTransaction(any(), any())).thenThrow(failure);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(InventoryLotsRead.class)
                        .list(scope, null, null, null, null, 0, 25, "expirationDate,asc"))
                        .isInstanceOf(com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException.class);
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(PickingWorkRead.class).list(scope, 0, 25))
                        .isInstanceOf(com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException.class);
                verifyNoInteractions(ctx.getBean(WarehouseOperationsRequestRunner.class),
                        ctx.getBean(FulfillmentDeliveryRequestRunner.class),
                        ctx.getBean(TenantFulfillmentDeliveryCompositionProviderAdapter.class));
            });
        }
    }

    @Test
    void cloudReadDoesNotChangePostCommandGuards() {
        context().withPropertyValues("nexa.tenant-business.cloud-operations-read.enabled=true").run(ctx -> {
            CurrentAccessContext scope = mock(CurrentAccessContext.class);
            var fallbackWarehouse = new TenantBoundWarehouseOperationsConfiguration().unavailableWarehouseOperationsRequestRunner();
            var warehouse = new com.nexa.api.inventoryavailability.presentation.WarehouseController(fallbackWarehouse,
                    mock(com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseOperationalSettingsCommands.class),
                    ctx.getBean(InventoryLotsRead.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> warehouse.blockLot(scope, "lot", null, "key",
                    new com.nexa.api.inventoryavailability.presentation.WarehouseController.ReasonRequest("reason"), null))
                    .isInstanceOf(com.nexa.api.inventoryavailability.application.publicapi.WarehouseOperationsStoreUnavailableException.class);
            var central = mock(FulfillmentDeliveryRequestRunner.class);
            var guarded = new CentralFallbackFulfillmentDeliveryRequestRunner(central, new CentralTenantBusinessDataAccessGuard());
            var fulfillment = new com.nexa.api.fulfillmentdelivery.presentation.FulfillmentController(guarded, ctx.getBean(PickingWorkRead.class));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> fulfillment.startPicking(scope, java.util.UUID.randomUUID(), null, "key"))
                    .isInstanceOf(com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException.class);
            verifyNoInteractions(central, ctx.getBean(TenantBusinessDatabaseRouter.class));
        });
    }

    @Test
    void warehouseGrantSnapshotFailsClosedAcrossTenantWorkspaceAndMembership() {
        CurrentAccessContext scope = scopedContext();
        var warehouse = java.util.UUID.randomUUID();
        var snapshot = TenantWarehouseRequestBindings.warehouseAccessSnapshot(scope, Set.of(warehouse));
        assertThat(snapshot.hasActiveGrant(scope, warehouse)).isTrue();
        CurrentAccessContext otherTenant = scopedContext();
        CurrentAccessContext otherWorkspace = scopedContext();
        CurrentAccessContext otherMembership = scopedContext();
        var tenantId = scope.tenantId();
        var workspaceId = scope.workspaceId();
        var membershipId = scope.membershipId();
        when(otherWorkspace.tenantId()).thenReturn(tenantId);
        when(otherWorkspace.membershipId()).thenReturn(membershipId);
        when(otherMembership.tenantId()).thenReturn(tenantId);
        when(otherMembership.workspaceId()).thenReturn(workspaceId);
        when(otherTenant.workspaceId()).thenReturn(workspaceId);
        when(otherTenant.membershipId()).thenReturn(membershipId);
        assertThat(snapshot.activeWarehouseIds(otherTenant)).isEmpty();
        assertThat(snapshot.activeWarehouseIds(otherWorkspace)).isEmpty();
        assertThat(snapshot.activeWarehouseIds(otherMembership)).isEmpty();
        assertThat(snapshot.hasActiveGrant(scope, java.util.UUID.randomUUID())).isFalse();
    }

    private static CurrentAccessContext scopedContext() {
        CurrentAccessContext scope = mock(CurrentAccessContext.class);
        when(scope.tenantId()).thenReturn(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId.random());
        when(scope.workspaceId()).thenReturn(WorkspaceId.random());
        when(scope.membershipId()).thenReturn(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId.random());
        return scope;
    }

    @Test
    void pickingPreflightIsOutsideRoutedWorkAndMissingGrantsOrPermissionFailClosed() {
        var scope = scopedContext();
        var inTenant = new java.util.concurrent.atomic.AtomicBoolean();
        var access = mock(WarehouseObjectAccess.class);
        when(access.activeWarehouseIds(scope)).thenAnswer(call -> {
            assertThat(inTenant.get()).as("central grant lookup must precede tenant transaction").isFalse();
            return Set.of();
        });
        var router = mock(TenantBusinessDatabaseRouter.class);
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(router.inReadOnlyTransaction(any(), any())).thenAnswer(call -> {
            assertThat(inTenant.getAndSet(true)).isFalse();
            try {
                com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseWork<?> work = call.getArgument(1);
                return work.execute(jdbc);
            } finally { inTenant.set(false); }
        });
        var provider = mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class);
        var persistence = mock(com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort.class);
        var allocations = mock(com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands.class);
        when(provider.bindTo(any(), any(), any())).thenAnswer(call -> {
            assertThat(inTenant.get()).isTrue();
            WarehouseObjectAccess snapshot = call.getArgument(1);
            assertThat(snapshot.activeWarehouseIds(scope)).isEmpty();
            var service = new com.nexa.api.fulfillmentdelivery.application.service.FulfillmentWorkListService(
                    persistence, allocations, snapshot, java.time.Clock.systemUTC());
            var composition = mock(com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition.class);
            when(composition.workList()).thenReturn(service);
            return composition;
        });
        var runner = new TenantBoundFulfillmentDeliveryRequestRunner(router, provider, access, mock(WorkforceDirectory.class));
        assertThat(runner.listPickingWork(scope, 0, 25).items()).isEmpty();
        verifyNoInteractions(persistence, allocations);
        doThrow(new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation("denied"))
                .when(scope).requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey.FULFILLMENT_READ);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.listPickingWork(scope, 0, 25))
                .isInstanceOf(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation.class);
        verifyNoInteractions(persistence, allocations);
        verify(access, times(2)).activeWarehouseIds(scope);
    }

    @Test
    void inventoryAdapterRejectsMissingWarehouseReadPermissionBeforeAnyQuery() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var scope = scopedContext();
        var warehouses = mock(WarehouseObjectAccess.class);
        var adapter = new com.nexa.api.inventoryavailability.infrastructure.persistence.WarehouseInventoryPersistenceAdapter(
                jdbc, null, null, null, null, null, null, warehouses, null);
        doThrow(new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation("denied"))
                .when(scope).requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission.WAREHOUSE_READ);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.lots(scope, null, null, null, null, 0, 25, "expirationDate,asc"))
                .isInstanceOf(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation.class);
        verifyNoInteractions(jdbc, warehouses);
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(MobileOperationsReadConfiguration.class)
                .withPropertyValues("spring.profiles.active=tenant-cloud")
                .withBean(WarehouseOperationsRequestRunner.class, () -> mock(WarehouseOperationsRequestRunner.class))
                .withBean(FulfillmentDeliveryRequestRunner.class, () -> mock(FulfillmentDeliveryRequestRunner.class))
                .withBean(TenantBusinessDatabaseRouter.class, () -> mock(TenantBusinessDatabaseRouter.class))
                .withBean(TenantWarehouseOperationsCompositionFactory.class, () -> mock(TenantWarehouseOperationsCompositionFactory.class))
                .withBean(TenantCustomerAccountQueryFactory.class, () -> mock(TenantCustomerAccountQueryFactory.class))
                .withBean(TenantSellableSkuQueryFactory.class, () -> mock(TenantSellableSkuQueryFactory.class))
                .withBean(TenantSalesOrderFulfillmentQueryFactory.class, () -> mock(TenantSalesOrderFulfillmentQueryFactory.class))
                .withBean(TenantFulfillmentInventoryQueryFactory.class, () -> mock(TenantFulfillmentInventoryQueryFactory.class))
                .withBean(TenantBusinessEvidenceQueryFactory.class, () -> mock(TenantBusinessEvidenceQueryFactory.class))
                .withBean(TenantChangeEventPersistenceFactory.class, () -> mock(TenantChangeEventPersistenceFactory.class))
                .withBean(TenantBusinessTraceabilityBindingsFactory.class, () -> mock(TenantBusinessTraceabilityBindingsFactory.class))
                .withBean(WarehouseObjectAccess.class, () -> mock(WarehouseObjectAccess.class))
                .withBean(OperationalSettingsAccess.class, () -> mock(OperationalSettingsAccess.class))
                .withBean(WorkforceDirectory.class, () -> mock(WorkforceDirectory.class))
                .withBean(TenantFulfillmentDeliveryCompositionProviderAdapter.class,
                        () -> mock(TenantFulfillmentDeliveryCompositionProviderAdapter.class));
    }
}
