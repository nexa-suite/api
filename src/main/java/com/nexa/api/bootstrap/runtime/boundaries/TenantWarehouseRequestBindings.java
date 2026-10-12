package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseAccessGrantView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class TenantWarehouseRequestBindings {
    private TenantWarehouseRequestBindings() { }

    static CatalogClientAccountPort catalogAccounts(CustomerAccountQuery accounts) {
        return new CatalogClientAccountPort() {
            @Override
            public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
                return accounts.findUnfilteredReferenceForMembership(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(UUID::fromString);
            }

            @Override
            public Optional<ClientAccountProfile> findProfileForMembership(UUID tenantId, UUID workspaceId,
                    UUID membershipId) {
                return accounts.findActiveBuyerDetails(tenantId.toString(), workspaceId.toString(),
                        membershipId.toString()).map(TenantWarehouseRequestBindings::profile);
            }

            @Override
            public Optional<ClientAccountProfile> findActiveProfile(UUID tenantId, UUID workspaceId,
                    UUID customerAccountId) {
                return accounts.findActiveDetails(tenantId.toString(), workspaceId.toString(),
                        customerAccountId.toString()).map(TenantWarehouseRequestBindings::profile);
            }
        };
    }

    static InventoryCommercialSource commercialSource(SalesOrderFulfillmentQuery orders) {
        return new InventoryCommercialSource() {
            @Override
            public Optional<Snapshot> findCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return lookup(tenantId, workspaceId, salesOrderId, false);
            }

            @Override
            public Optional<Snapshot> claimCandidate(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return lookup(tenantId, workspaceId, salesOrderId, true);
            }

            private Optional<Snapshot> lookup(UUID tenantId, UUID workspaceId, UUID salesOrderId, boolean claim) {
                try {
                    SalesOrderFulfillmentQuery.Snapshot order = claim
                            ? orders.getForUpdate(tenantId, workspaceId, salesOrderId)
                            : orders.get(tenantId, workspaceId, salesOrderId);
                    return Optional.of(new Snapshot(order.id(), order.number(), order.status(), order.version(),
                            order.clientAccountId(), order.commercialCommitmentId(), order.destinationSnapshot(),
                            order.lines().stream().map(line -> new Line(line.id(), line.skuId(),
                                    line.catalogItemId(), line.quantity(), line.unit())).toList()));
                } catch (CommercialBusinessException exception) {
                    if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
                    return Optional.empty();
                }
            }
        };
    }

    static InventoryFulfillmentSource fulfillmentSource(FulfillmentInventoryQuery query) {
        return new InventoryFulfillmentSource() {
            @Override
            public boolean hasFulfillment(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return query.hasFulfillment(tenantId, workspaceId, salesOrderId);
            }

            @Override
            public boolean hasActiveDispatch(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return query.hasActiveDispatch(tenantId, workspaceId, salesOrderId);
            }

            @Override
            public Optional<UUID> physicalAllocationForDelivery(UUID tenantId, UUID workspaceId, UUID deliveryId) {
                return query.physicalAllocationForDelivery(tenantId, workspaceId, deliveryId);
            }
        };
    }

    static WarehouseObjectAccess warehouseAccessSnapshot(CurrentAccessContext context,
                                                           Set<UUID> warehouseIds) {
        return new WarehouseAccessSnapshot(context, warehouseIds);
    }

    private static CatalogClientAccountPort.ClientAccountProfile profile(CustomerAccountDetails account) {
        return new CatalogClientAccountPort.ClientAccountProfile(UUID.fromString(account.id()), account.segment(), null);
    }

    private static final class WarehouseAccessSnapshot implements WarehouseObjectAccess {
        private final CurrentAccessContext scope;
        private final Set<UUID> warehouseIds;

        private WarehouseAccessSnapshot(CurrentAccessContext scope, Set<UUID> warehouseIds) {
            this.scope = scope;
            this.warehouseIds = Set.copyOf(warehouseIds);
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
                    && tenantId.equals(scope.tenantId().value()) && workspaceId.equals(scope.workspaceId().value())
                    && membershipId.equals(scope.membershipId().value()) && warehouseIds.contains(warehouseId);
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
            return new AccessPolicyViolation("Warehouse administration is outside this Tenant request snapshot");
        }
    }
}
