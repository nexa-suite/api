package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.fulfillmentdelivery.application.FulfillmentDeliveryComposition;
import com.nexa.api.fulfillmentdelivery.tenantdatabase.TenantFulfillmentDeliveryCompositionFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Captures central BC-01 logistics authority before a Tenant event is applied. */
@Component
public final class TenantLogisticsEventCompositionBinder {
    private final TenantFulfillmentDeliveryCompositionFactory compositions;
    private final WarehouseObjectAccess warehouseAccess;

    public TenantLogisticsEventCompositionBinder(TenantFulfillmentDeliveryCompositionFactory compositions,
            WarehouseObjectAccess warehouseAccess) {
        this.compositions = Objects.requireNonNull(compositions, "Tenant BC-06 composition factory is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Central Warehouse access is required");
    }

    /** Call outside the Tenant router callback. */
    public Preflight preflight(CurrentAccessContext verifiedActor) {
        CurrentAccessContext actor = Objects.requireNonNull(verifiedActor, "Verified workflow actor is required");
        return new Preflight(actor, Set.copyOf(warehouseAccess.activeWarehouseIds(actor)));
    }

    /** Call inside the event's fresh Tenant router callback. */
    public FulfillmentDeliveryComposition bindTo(JdbcTemplate tenantJdbc, Preflight preflight) {
        Objects.requireNonNull(preflight, "Central logistics preflight is required");
        return Objects.requireNonNull(compositions.bindTo(Objects.requireNonNull(tenantJdbc),
                TenantWarehouseRequestBindings.warehouseAccessSnapshot(preflight.actor(), preflight.warehouseIds()),
                WORKFORCE_SNAPSHOT), "Tenant BC-06 composition factory returned no composition");
    }

    public record Preflight(CurrentAccessContext actor, Set<UUID> warehouseIds) {
        public Preflight {
            Objects.requireNonNull(actor, "Verified workflow actor is required");
            warehouseIds = Set.copyOf(Objects.requireNonNull(warehouseIds, "Warehouse access snapshot is required"));
        }
    }

    /* FULFILLMENT_READY creates a dispatch; that owner command uses no workforce lookup. */
    private static final WorkforceDirectory WORKFORCE_SNAPSHOT = new WorkforceDirectory() {
        @Override public boolean membershipExists(UUID tenantId, UUID workspaceId, UUID membershipId) { return false; }
        @Override public Optional<String> findAssignableLogisticsName(UUID tenantId, UUID workspaceId,
                UUID membershipId) { return Optional.empty(); }
        @Override public List<LogisticsAssignee> findLogisticsAssignees(UUID tenantId, UUID workspaceId) {
            return List.of();
        }
        @Override public Set<UUID> filterActiveBuyerMembershipIds(UUID tenantId, UUID workspaceId,
                List<UUID> membershipIds) { return Set.of(); }
    };
}
