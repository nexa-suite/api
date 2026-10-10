package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.publicapi.NotificationRecipientPreflightPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantEventContextQueryPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Central ACL preflight; caller must finish it before opening the Tenant worker route. */
@Component
@Profile("!test")
public final class TenantNotificationRecipientPreflightAdapter implements NotificationRecipientPreflightPort {
    private static final Set<String> INTERNAL_NOTIFICATION_ROLES = Set.of("sales", "company_owner", "tenant_admin");
    private static final Set<String> BUYER_NOTIFICATION_EVENTS = Set.of("DISPATCH_DELIVERED", "PAYMENT_SUCCEEDED");

    private final TenantEventContextQueryPort tenantContext;
    private final WorkforceDirectory workforce;
    private final WarehouseObjectAccess warehouseAccess;

    public TenantNotificationRecipientPreflightAdapter(TenantEventContextQueryPort tenantContext,
            WorkforceDirectory workforce, WarehouseObjectAccess warehouseAccess) {
        this.tenantContext = Objects.requireNonNull(tenantContext, "Central Tenant membership query is required");
        this.workforce = Objects.requireNonNull(workforce, "Central active Buyer membership filter is required");
        this.warehouseAccess = Objects.requireNonNull(warehouseAccess, "Current Warehouse grant query is required");
    }

    @Override
    public Set<UUID> findEligibleMembershipIds(UUID tenantId, UUID workspaceId, String eventType,
            String aggregateType, UUID aggregateId, UUID clientAccountId, Set<UUID> tenantBuyerMembershipIds) {
        Objects.requireNonNull(tenantId, "Tenant scope is required");
        Objects.requireNonNull(workspaceId, "Workspace scope is required");
        if (eventType == null || eventType.isBlank()) throw new IllegalArgumentException("Notification event type is required");

        Set<UUID> eligible = new HashSet<>(tenantContext.findActiveMembershipIdsByRoleCodes(tenantId,
                workspaceId, INTERNAL_NOTIFICATION_ROLES));
        requireNotificationPermission(MembershipRole.SALES);
        requireNotificationPermission(MembershipRole.COMPANY_OWNER);
        requireNotificationPermission(MembershipRole.TENANT_ADMIN);

        if (isWarehouseObject(aggregateType, aggregateId)) {
            requireNotificationPermission(MembershipRole.WAREHOUSE);
            Set<UUID> warehouseMembers = tenantContext.findActiveMembershipIdsByRoleCodes(tenantId,
                    workspaceId, Set.of("warehouse"));
            for (UUID membershipId : warehouseMembers) {
                if (warehouseAccess.hasActiveGrant(tenantId, workspaceId, membershipId, aggregateId)) {
                    eligible.add(membershipId);
                }
            }
        }

        if (BUYER_NOTIFICATION_EVENTS.contains(eventType) && clientAccountId != null
                && tenantBuyerMembershipIds != null && !tenantBuyerMembershipIds.isEmpty()) {
            eligible.addAll(workforce.filterActiveBuyerMembershipIds(tenantId, workspaceId,
                    List.copyOf(tenantBuyerMembershipIds)));
        }
        return Set.copyOf(eligible);
    }

    private static boolean isWarehouseObject(String aggregateType, UUID aggregateId) {
        return aggregateType != null && aggregateType.equalsIgnoreCase("warehouse") && aggregateId != null;
    }

    private static void requireNotificationPermission(MembershipRole role) {
        if (!PermissionCatalog.forBuiltInRole(role).contains(PermissionKey.NOTIFICATION_READ)) {
            throw new IllegalStateException("Built-in notification recipient role lacks notification.read permission");
        }
    }
}
