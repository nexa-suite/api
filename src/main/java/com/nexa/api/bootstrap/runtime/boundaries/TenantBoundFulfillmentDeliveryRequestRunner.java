package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.application.port.FulfillmentDeliveryRequestRunner;
import com.nexa.api.fulfillmentdelivery.application.service.DriverTrackingService;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseAccessGrantView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Performs central authority snapshots before borrowing a Tenant connection, then runs one routed unit of work. */
public final class TenantBoundFulfillmentDeliveryRequestRunner implements FulfillmentDeliveryRequestRunner {
    private final TenantBusinessDatabaseRouter router;
    private final TenantFulfillmentDeliveryCompositionFactory compositions;
    private final WarehouseObjectAccess centralWarehouseAccess;
    private final WorkforceDirectory centralWorkforce;

    public TenantBoundFulfillmentDeliveryRequestRunner(TenantBusinessDatabaseRouter router,
                                                       TenantFulfillmentDeliveryCompositionFactory compositions,
                                                       WarehouseObjectAccess centralWarehouseAccess,
                                                       WorkforceDirectory centralWorkforce) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.compositions = Objects.requireNonNull(compositions, "Tenant BC-06 composition factory is required");
        this.centralWarehouseAccess = Objects.requireNonNull(centralWarehouseAccess,
                "BC-01 Warehouse access is required for preflight");
        this.centralWorkforce = Objects.requireNonNull(centralWorkforce,
                "BC-01 workforce is required for preflight");
    }

    @Override
    public <T> T execute(CurrentAccessContext context, Requirements requirements,
                         Function<FulfillmentDeliveryComposition, T> work) {
        CurrentAccessContext verified = Objects.requireNonNull(context, "Verified access context is required");
        Requirements needed = Objects.requireNonNull(requirements, "Preflight requirements are required");
        Function<FulfillmentDeliveryComposition, T> action = Objects.requireNonNull(work,
                "BC-06 request work is required");

        if (needed.storedDriverLocation()) {
            throw DriverTrackingService.error("DRIVER_LOCATION_UNAVAILABLE", false);
        }

        Set<UUID> warehouses = needed.warehouseGrants()
                ? Set.copyOf(centralWarehouseAccess.activeWarehouseIds(verified)) : Set.of();
        Map<UUID, String> assignable = new HashMap<>();
        if (needed.currentDriver()) {
            UUID membershipId = verified.membershipId().value();
            centralWorkforce.findAssignableLogisticsName(verified.tenantId().value(),
                            verified.workspaceId().value(), membershipId)
                    .ifPresent(name -> assignable.put(membershipId, name));
        }
        for (UUID membershipId : needed.assignableMemberships()) {
            centralWorkforce.findAssignableLogisticsName(verified.tenantId().value(),
                            verified.workspaceId().value(), membershipId)
                    .ifPresent(name -> assignable.put(membershipId, name));
        }
        List<WorkforceDirectory.LogisticsAssignee> assignees = needed.logisticsAssignees()
                ? List.copyOf(centralWorkforce.findLogisticsAssignees(verified.tenantId().value(),
                verified.workspaceId().value())) : List.of();

        WarehouseObjectAccess warehouseSnapshot = new WarehouseAccessSnapshot(verified, warehouses);
        WorkforceDirectory workforceSnapshot = new WorkforceSnapshot(verified, assignable, assignees,
                needed.logisticsAssignees());
        return router.inTransaction(verified, tenantJdbc -> action.apply(
                compositions.bindTo(tenantJdbc, warehouseSnapshot, workforceSnapshot)));
    }

    private static final class WarehouseAccessSnapshot implements WarehouseObjectAccess {
        private final CurrentAccessContext scope;
        private final Set<UUID> warehouseIds;

        private WarehouseAccessSnapshot(CurrentAccessContext scope, Set<UUID> warehouseIds) {
            this.scope = scope;
            this.warehouseIds = warehouseIds;
        }

        @Override
        public void authorizeAdministration(CurrentAccessContext context) {
            throw unavailable();
        }

        @Override
        public Set<UUID> activeWarehouseIds(CurrentAccessContext context) {
            return sameMembershipScope(context) ? warehouseIds : Set.of();
        }

        @Override
        public boolean hasActiveGrant(CurrentAccessContext context, UUID warehouseId) {
            return sameMembershipScope(context) && warehouseIds.contains(warehouseId);
        }

        @Override
        public boolean hasActiveGrant(UUID tenantId, UUID workspaceId, UUID membershipId, UUID warehouseId) {
            return tenantId != null && workspaceId != null && membershipId != null
                    && tenantId.equals(scope.tenantId().value())
                    && workspaceId.equals(scope.workspaceId().value())
                    && membershipId.equals(scope.membershipId().value())
                    && warehouseIds.contains(warehouseId);
        }

        @Override
        public List<WarehouseAccessGrantView> grants(CurrentAccessContext context, UUID warehouseId) {
            throw unavailable();
        }

        @Override
        public WarehouseAccessGrantView grant(CurrentAccessContext context, UUID warehouseId,
                                              UUID targetMembershipId, Long expectedVersion, String correlationId) {
            throw unavailable();
        }

        @Override
        public WarehouseAccessGrantView revoke(CurrentAccessContext context, UUID warehouseId,
                                               UUID targetMembershipId, long expectedVersion, String correlationId) {
            throw unavailable();
        }

        private boolean sameMembershipScope(CurrentAccessContext context) {
            return context != null && context.tenantId().equals(scope.tenantId())
                    && context.workspaceId().equals(scope.workspaceId())
                    && context.membershipId().equals(scope.membershipId());
        }

        private static AccessPolicyViolation unavailable() {
            return new AccessPolicyViolation("Warehouse administration is not available in a Tenant request snapshot");
        }
    }

    private static final class WorkforceSnapshot implements WorkforceDirectory {
        private final CurrentAccessContext scope;
        private final Map<UUID, String> assignableNames;
        private final List<LogisticsAssignee> assignees;
        private final boolean assigneesLoaded;

        private WorkforceSnapshot(CurrentAccessContext scope, Map<UUID, String> assignableNames,
                                  List<LogisticsAssignee> assignees, boolean assigneesLoaded) {
            this.scope = scope;
            this.assignableNames = Map.copyOf(assignableNames);
            this.assignees = List.copyOf(assignees);
            this.assigneesLoaded = assigneesLoaded;
        }

        @Override
        public boolean membershipExists(UUID tenantId, UUID workspaceId, UUID membershipId) {
            return inTenantWorkspace(tenantId, workspaceId) && assignableNames.containsKey(membershipId);
        }

        @Override
        public java.util.Optional<String> findAssignableLogisticsName(UUID tenantId, UUID workspaceId,
                                                                       UUID membershipId) {
            return inTenantWorkspace(tenantId, workspaceId)
                    ? java.util.Optional.ofNullable(assignableNames.get(membershipId)) : java.util.Optional.empty();
        }

        @Override
        public List<LogisticsAssignee> findLogisticsAssignees(UUID tenantId, UUID workspaceId) {
            return assigneesLoaded && inTenantWorkspace(tenantId, workspaceId) ? assignees : List.of();
        }

        @Override
        public Set<UUID> filterActiveBuyerMembershipIds(UUID tenantId, UUID workspaceId,
                                                         List<UUID> membershipIds) {
            return Set.of();
        }

        private boolean inTenantWorkspace(UUID tenantId, UUID workspaceId) {
            return tenantId != null && workspaceId != null && tenantId.equals(scope.tenantId().value())
                    && workspaceId.equals(scope.workspaceId().value());
        }
    }
}
