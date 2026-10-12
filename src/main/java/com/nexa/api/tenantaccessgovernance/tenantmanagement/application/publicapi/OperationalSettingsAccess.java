package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;

import java.time.LocalTime;
import java.util.Optional;

/** Owner boundary for existing warehouse operational settings. */
public interface OperationalSettingsAccess {
    Optional<Snapshot> find(String workspaceId);
    int update(String workspaceId, String selectionPolicy, LocalTime startsAt, LocalTime endsAt, long expectedVersion);
    /**
     * Reads the central expiry-policy source without creating default rows.
     * An empty result means the Tenant/Workspace pair is not centrally valid;
     * CONFIRMED_ABSENT is returned only when that Workspace exists and its
     * operational-settings row does not.
     */
    Optional<PurchaseRequestExpiryPolicySource> findPurchaseRequestExpiryPolicy(
            TenantId tenantId, WorkspaceId workspaceId);
    public record Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                           String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
                           LocalTime startsAt, LocalTime endsAt, int orderCutoffMinutes,
                           boolean thermalLogRequired, long version) { }

    record PurchaseRequestExpiryPolicySource(TenantId tenantId, WorkspaceId workspaceId,
            SourceState sourceState, Long sourceVersion, int expiryDays) {
        public PurchaseRequestExpiryPolicySource {
            java.util.Objects.requireNonNull(tenantId, "Tenant id is required");
            java.util.Objects.requireNonNull(workspaceId, "Workspace id is required");
            java.util.Objects.requireNonNull(sourceState, "Source state is required");
            if (sourceState == SourceState.PRESENT) {
                if (sourceVersion == null || sourceVersion < 0 || expiryDays < 1 || expiryDays > 7) {
                    throw new IllegalArgumentException("Present central expiry policy is invalid");
                }
            } else if (sourceVersion != null || expiryDays != 3) {
                throw new IllegalArgumentException("Confirmed absence uses the accepted three-day fallback");
            }
        }
    }

    enum SourceState { PRESENT, CONFIRMED_ABSENT }
}
