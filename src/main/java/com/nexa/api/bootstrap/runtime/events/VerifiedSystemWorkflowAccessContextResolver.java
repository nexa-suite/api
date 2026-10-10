package com.nexa.api.bootstrap.runtime.events;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.TenantEventContextQueryPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Resolves the persisted BC-01 SYSTEM_WORKFLOW actor for one exact Tenant/Workspace scope. */
@Component
public final class VerifiedSystemWorkflowAccessContextResolver {
    private final TenantEventContextQueryPort actors;
    private final ResolveCurrentAccessContextUseCase access;

    public VerifiedSystemWorkflowAccessContextResolver(TenantEventContextQueryPort actors,
            ResolveCurrentAccessContextUseCase access) {
        this.actors = Objects.requireNonNull(actors, "Tenant workflow actor query is required");
        this.access = Objects.requireNonNull(access, "Current access-context resolver is required");
    }

    public CurrentAccessContext resolve(UUID tenantIdValue, UUID workspaceIdValue) {
        TenantId tenantId = new TenantId(Objects.requireNonNull(tenantIdValue, "Tenant scope is required"));
        WorkspaceId workspaceId = new WorkspaceId(Objects.requireNonNull(workspaceIdValue, "Workspace scope is required"));
        TenantEventContextQueryPort.WorkflowActor principal = actors.findSystemWorkflowActor(
                tenantId.value(), workspaceId.value());
        if (!TenantEventContextQueryPort.SYSTEM_WORKFLOW_MEMBERSHIP_TYPE.equals(principal.membershipType())
                || !TenantEventContextQueryPort.SYSTEM_WORKFLOW_ROLE_CODE.equals(principal.roleCode())
                || !TenantEventContextQueryPort.NEXA_AUTOMATION_IDENTITY.equals(principal.identity())) {
            throw new IllegalStateException("Workflow actor is not the persisted SYSTEM_WORKFLOW/NEXA_AUTOMATION principal");
        }
        CurrentAccessContext context = access.resolve(new CurrentAccessRequest(new UserId(principal.userId()),
                tenantId, workspaceId, Surface.PLATFORM));
        if (!tenantId.equals(context.tenantId()) || !workspaceId.equals(context.workspaceId())
                || !principal.membershipId().equals(context.membershipId().value())
                || !principal.userId().equals(context.userId().value())
                || !context.hasRoleCode(TenantEventContextQueryPort.SYSTEM_WORKFLOW_ROLE_CODE)) {
            throw new IllegalStateException("Resolved workflow actor does not match the exact Tenant/Workspace SYSTEM_WORKFLOW scope");
        }
        return context;
    }
}
