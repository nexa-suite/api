package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.ConcurrencyConflictException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.OrganizationPreconditionRequiredException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.WarehouseAccessGrantPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseAccessGrantView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.WarehouseAccessGrant;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.WarehouseAccessGrantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Evaluates current BC-01 membership and Warehouse grant authority for BC-05 callers. */
public final class WarehouseObjectAccessService implements WarehouseObjectAccess {
    private final WarehouseAccessGrantPersistencePort grants;
    private final AuthorizationVersionPort authorizationVersions;
    private final ChangeEventPersistencePort changes;
    private final SecurityAuditPort audit;

    public WarehouseObjectAccessService(WarehouseAccessGrantPersistencePort grants,
                                        AuthorizationVersionPort authorizationVersions,
                                        ChangeEventPersistencePort changes, SecurityAuditPort audit) {
        this.grants = Objects.requireNonNull(grants, "Warehouse grant persistence is required");
        this.authorizationVersions = Objects.requireNonNull(authorizationVersions, "Authorization versions are required");
        this.changes = Objects.requireNonNull(changes, "Change events are required");
        this.audit = Objects.requireNonNull(audit, "Security audit is required");
    }

    @Override
    public void authorizeAdministration(CurrentAccessContext context) {
        requireAdministrator(context);
        requireCurrentActor(context);
    }

    @Override
    public Set<UUID> activeWarehouseIds(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Current access context is required");
        return grants.activeWarehouseIds(context.tenantId(), context.workspaceId(), context.membershipId());
    }

    @Override
    public boolean hasActiveGrant(CurrentAccessContext context, UUID warehouseId) {
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        return activeWarehouseIds(context).contains(warehouseId);
    }

    @Override
    public boolean hasActiveGrant(UUID tenantId, UUID workspaceId, UUID membershipId, UUID warehouseId) {
        Objects.requireNonNull(warehouseId, "Warehouse id is required");
        return grants.activeWarehouseIds(new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId(tenantId),
                new com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId(workspaceId),
                new MembershipId(membershipId)).contains(warehouseId);
    }

    @Override
    public List<WarehouseAccessGrantView> grants(CurrentAccessContext context, UUID warehouseId) {
        requireAdministrator(context);
        requireCurrentActor(context);
        return grants.findForWarehouse(context.tenantId(), context.workspaceId(), requiredWarehouse(warehouseId))
                .stream().map(WarehouseObjectAccessService::view).toList();
    }

    @Override
    public WarehouseAccessGrantView grant(CurrentAccessContext context, UUID warehouseId, UUID targetMembershipId,
                                          Long expectedVersion, String correlationId) {
        requireAdministrator(context);
        requireCurrentActor(context);
        UUID warehouse = requiredWarehouse(warehouseId);
        MembershipId target = new MembershipId(Objects.requireNonNull(targetMembershipId, "Membership id is required"));
        requireActiveTarget(context, target);
        var current = grants.find(context.tenantId(), context.workspaceId(), target, warehouse);
        if (current.isEmpty()) {
            if (expectedVersion != null) throw new ConcurrencyConflictException();
            WarehouseAccessGrant created = new WarehouseAccessGrant(context.tenantId(), context.workspaceId(), target,
                    warehouse, WarehouseAccessGrantStatus.ACTIVE, 0, context.membershipId(), Instant.now());
            WarehouseAccessGrant persisted = grants.insert(created);
            publish(context, persisted, "tenant.warehouse-access-grant.created", "WAREHOUSE_ACCESS_GRANTED", correlationId);
            return view(persisted);
        }
        WarehouseAccessGrant persisted = current.get();
        if (expectedVersion == null) {
            if (persisted.status() == WarehouseAccessGrantStatus.ACTIVE) return view(persisted);
            throw new OrganizationPreconditionRequiredException();
        }
        if (expectedVersion.longValue() != persisted.version()) throw new ConcurrencyConflictException();
        if (persisted.status() == WarehouseAccessGrantStatus.ACTIVE) return view(persisted);
        WarehouseAccessGrant activated = transition(persisted, () -> persisted.activate(expectedVersion, context.membershipId(), Instant.now()));
        if (grants.update(activated, expectedVersion) != 1) throw new ConcurrencyConflictException();
        publish(context, activated, "tenant.warehouse-access-grant.activated", "WAREHOUSE_ACCESS_GRANTED", correlationId);
        return view(activated);
    }

    @Override
    public WarehouseAccessGrantView revoke(CurrentAccessContext context, UUID warehouseId, UUID targetMembershipId,
                                           long expectedVersion, String correlationId) {
        requireAdministrator(context);
        requireCurrentActor(context);
        WarehouseAccessGrant current = grants.find(context.tenantId(), context.workspaceId(),
                        new MembershipId(Objects.requireNonNull(targetMembershipId, "Membership id is required")),
                        requiredWarehouse(warehouseId))
                .orElseThrow(ConcurrencyConflictException::new);
        if (current.version() != expectedVersion) throw new ConcurrencyConflictException();
        if (current.status() == WarehouseAccessGrantStatus.REVOKED) return view(current);
        WarehouseAccessGrant revoked = transition(current,
                () -> current.revoke(expectedVersion, context.membershipId(), Instant.now()));
        if (grants.update(revoked, expectedVersion) != 1) throw new ConcurrencyConflictException();
        publish(context, revoked, "tenant.warehouse-access-grant.revoked", "WAREHOUSE_ACCESS_REVOKED", correlationId);
        return view(revoked);
    }

    private void requireAdministrator(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Current access context is required");
        context.requirePermission(PermissionKey.TENANT_ROLE_ASSIGN);
    }

    private void requireCurrentActor(CurrentAccessContext context) {
        if (!grants.isActiveScope(context.tenantId(), context.workspaceId(), context.membershipId())) {
            throw new AccessPolicyViolation("Current membership is no longer active in this tenant workspace");
        }
    }

    private void requireActiveTarget(CurrentAccessContext context, MembershipId target) {
        if (!grants.isActiveMembership(context.tenantId(), context.workspaceId(), target)) {
            throw new AccessPolicyViolation("Warehouse access can only be granted to an active workspace membership");
        }
    }

    private void publish(CurrentAccessContext context, WarehouseAccessGrant grant, String eventType,
                         String auditType, String correlationId) {
        authorizationVersions.bump(context.tenantId(), context.workspaceId(), grant.membershipId());
        changes.append(context.tenantId().toString(), context.workspaceId().toString(), null,
                "warehouse-access-grants", grant.warehouseId().toString(), eventType,
                grant.status().name(), grant.changedAt().toEpochMilli(), false);
        audit.append(new SecurityAuditPort.Event(auditType, context.userId().value(), null,
                context.tenantId().value(), context.workspaceId().value(), context.surface().name(),
                correlationId == null || correlationId.isBlank() ? "unknown" : correlationId, "unknown",
                grant.changedAt(), Map.of("warehouseId", grant.warehouseId().toString(),
                        "targetMembershipId", grant.membershipId().toString(), "grantVersion", grant.version(),
                        "status", grant.status().name())));
    }

    private static UUID requiredWarehouse(UUID warehouseId) {
        return Objects.requireNonNull(warehouseId, "Warehouse id is required");
    }

    private static WarehouseAccessGrantView view(WarehouseAccessGrant grant) {
        return new WarehouseAccessGrantView(grant.tenantId().value(), grant.workspaceId().value(),
                grant.membershipId().value(), grant.warehouseId(), grant.status().name(), grant.version(),
                grant.changedBy().value(), grant.changedAt());
    }

    private static WarehouseAccessGrant transition(WarehouseAccessGrant grant,
                                                    java.util.function.Supplier<WarehouseAccessGrant> change) {
        try {
            return change.get();
        } catch (IllegalStateException exception) {
            throw new ConcurrencyConflictException();
        }
    }
}
