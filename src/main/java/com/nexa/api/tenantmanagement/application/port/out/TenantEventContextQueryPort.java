package com.nexa.api.tenantmanagement.application.port.out;

import java.util.Set;
import java.util.UUID;

/**
 * Published-language read boundary for scoped memberships and the technical
 * principal used by asynchronous service workflows.
 */
public interface TenantEventContextQueryPort {
    String SYSTEM_WORKFLOW_MEMBERSHIP_TYPE = "SYSTEM_WORKFLOW";
    String SYSTEM_WORKFLOW_ROLE_CODE = "system_workflow";
    String NEXA_AUTOMATION_IDENTITY = "NEXA_AUTOMATION";
    String NEXA_AUTOMATION_EMAIL = "nexa-automation@system.invalid";

    WorkflowActor findSystemWorkflowActor(UUID tenantId, UUID workspaceId);

    Set<UUID> findActiveMembershipIdsByRoleCodes(UUID tenantId, UUID workspaceId, Set<String> roleCodes);

    record WorkflowActor(UUID userId, UUID membershipId, String membershipType, String roleCode, String identity) {
        public WorkflowActor {
            if (userId == null || membershipId == null || membershipType == null || roleCode == null || identity == null) {
                throw new IllegalArgumentException("Workflow actor identity is incomplete");
            }
        }
    }
}
