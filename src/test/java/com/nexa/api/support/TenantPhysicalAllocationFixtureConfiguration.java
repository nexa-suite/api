package com.nexa.api.support;

import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationCommands;
import com.nexa.api.inventoryavailability.infrastructure.persistence.JdbcTenantPhysicalAllocationCommandsFactory;
import com.nexa.api.inventoryavailability.tenantdatabase.TenantPhysicalAllocationCommandsFactory;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Tenant-bound physical-allocation failpoints for transaction and readiness integration tests. */
@TestConfiguration(proxyBeanMethods = false)
public class TenantPhysicalAllocationFixtureConfiguration {
    @Bean
    TenantPhysicalAllocationFixture tenantPhysicalAllocationFixture() {
        return new TenantPhysicalAllocationFixture();
    }

    @Bean
    @Primary
    TenantPhysicalAllocationCommandsFactory fixturePhysicalAllocationCommandsFactory(
            JdbcTenantPhysicalAllocationCommandsFactory delegate, TenantPhysicalAllocationFixture fixture) {
        return new TenantPhysicalAllocationCommandsFactory() {
            @Override
            public PhysicalAllocationCommands bindTo(JdbcTemplate tenantJdbc,
                    BusinessTraceabilityCommands tenantTraceability,
                    InventoryCommercialSource tenantCommercialSource,
                    InventoryFulfillmentSource tenantFulfillmentSource,
                    SellableSkuQuery tenantSellableSkus,
                    CanonicalOutboxPort tenantCanonicalOutbox,
                    WarehouseObjectAccess verifiedWarehouseAccess) {
                PhysicalAllocationCommands commands = delegate.bindTo(tenantJdbc, tenantTraceability,
                        tenantCommercialSource, tenantFulfillmentSource, tenantSellableSkus,
                        tenantCanonicalOutbox, verifiedWarehouseAccess);
                return new FixtureCommands(commands, fixture);
            }
        };
    }

    public static final class TenantPhysicalAllocationFixture {
        private final AtomicReference<Mode> nextMode = new AtomicReference<>(Mode.NONE);
        private final AtomicBoolean failureTriggered = new AtomicBoolean();

        public void releaseNextAllocation() {
            arm(Mode.RELEASE);
        }

        public void failAfterNextAllocation() {
            failureTriggered.set(false);
            arm(Mode.FAIL_AFTER_ALLOCATION);
        }

        public boolean failureWasTriggered() {
            return failureTriggered.get();
        }

        private void arm(Mode mode) {
            if (!nextMode.compareAndSet(Mode.NONE, mode)) {
                throw new IllegalStateException("A physical allocation fixture action is already armed");
            }
        }

        private Mode takeMode() {
            return nextMode.getAndSet(Mode.NONE);
        }
    }

    private enum Mode { NONE, RELEASE, FAIL_AFTER_ALLOCATION }

    private static final class FixtureCommands implements PhysicalAllocationCommands {
        private final PhysicalAllocationCommands delegate;
        private final TenantPhysicalAllocationFixture fixture;

        private FixtureCommands(PhysicalAllocationCommands delegate, TenantPhysicalAllocationFixture fixture) {
            this.delegate = delegate;
            this.fixture = fixture;
        }

        @Override
        public AllocationResult getByFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                                 UUID actorMembershipId) {
            return delegate.getByFulfillment(tenantId, workspaceId, fulfillmentId, actorMembershipId);
        }

        @Override
        public void lockForFulfillment(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                       UUID actorMembershipId) {
            delegate.lockForFulfillment(tenantId, workspaceId, fulfillmentId, actorMembershipId);
        }

        @Override
        public void lockLotsForPicking(UUID tenantId, UUID workspaceId, UUID fulfillmentId,
                                       UUID actorMembershipId, List<UUID> candidateLotIds) {
            delegate.lockLotsForPicking(tenantId, workspaceId, fulfillmentId, actorMembershipId, candidateLotIds);
        }

        @Override
        public AllocationResult allocate(AllocationRequest request) {
            Mode mode = fixture.takeMode();
            AllocationResult allocation = delegate.allocate(request);
            if (mode == Mode.RELEASE) {
                String key = "readiness-fixture-release-" + request.fulfillmentId();
                delegate.release(new ReleaseRequest(request.tenantId(), request.workspaceId(),
                        request.fulfillmentId(), request.actorMembershipId(), key,
                        sha256("release|" + request.fulfillmentId() + "|" + key), allocation.version(),
                        "Readiness integration fixture release", request.now()));
            } else if (mode == Mode.FAIL_AFTER_ALLOCATION) {
                fixture.failureTriggered.set(true);
                throw new IllegalStateException("failure-injection-after-physical-allocation");
            }
            return allocation;
        }

        @Override
        public PickingScanValidationResult validatePickingScan(PickingScanValidationRequest request) {
            return delegate.validatePickingScan(request);
        }

        @Override
        public AllocationResult consumeForDispatch(ConsumeRequest request) {
            return delegate.consumeForDispatch(request);
        }

        @Override
        public AllocationResult reconcileUnpicked(ReconcileUnpickedRequest request) {
            return delegate.reconcileUnpicked(request);
        }

        @Override
        public void release(ReleaseRequest request) {
            delegate.release(request);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
