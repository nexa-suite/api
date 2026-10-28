package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.configuration.OperationalSettings;

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
    /** Reads the delivery lead-time source without creating a missing settings row. */
    Optional<DeliveryLeadTimePolicySource> findDeliveryLeadTimePolicy(
            TenantId tenantId, WorkspaceId workspaceId);
    public record Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                           String inventoryVisibilityPolicy, String buyerAvailabilityPolicy,
                           LocalTime startsAt, LocalTime endsAt, int orderCutoffMinutes,
                           boolean thermalLogRequired, int minimumDeliveryLeadDays, long version) {
        public Snapshot(String selectionPolicy, String orderCutoffPolicy, String fulfillmentDefaults,
                String inventoryVisibilityPolicy, String buyerAvailabilityPolicy, LocalTime startsAt,
                LocalTime endsAt, int orderCutoffMinutes, boolean thermalLogRequired, long version) {
            this(selectionPolicy, orderCutoffPolicy, fulfillmentDefaults, inventoryVisibilityPolicy,
                    buyerAvailabilityPolicy, startsAt, endsAt, orderCutoffMinutes, thermalLogRequired,
                    OperationalSettings.DEFAULT_MINIMUM_DELIVERY_LEAD_DAYS, version);
        }
    }

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

    record DeliveryLeadTimePolicySource(TenantId tenantId, WorkspaceId workspaceId,
            SourceState sourceState, Long sourceVersion, int minimumDeliveryLeadDays) {
        public DeliveryLeadTimePolicySource {
            java.util.Objects.requireNonNull(tenantId, "Tenant id is required");
            java.util.Objects.requireNonNull(workspaceId, "Workspace id is required");
            java.util.Objects.requireNonNull(sourceState, "Source state is required");
            if (sourceState == SourceState.PRESENT) {
                if (sourceVersion == null || sourceVersion < 0 || minimumDeliveryLeadDays < 0) {
                    throw new IllegalArgumentException("Present central delivery lead-time policy is invalid");
                }
            } else if (sourceVersion != null
                    || minimumDeliveryLeadDays != OperationalSettings.DEFAULT_MINIMUM_DELIVERY_LEAD_DAYS) {
                throw new IllegalArgumentException("Confirmed absence uses the accepted three-day default");
            }
        }
    }

    enum SourceState { PRESENT, CONFIRMED_ABSENT }
}
