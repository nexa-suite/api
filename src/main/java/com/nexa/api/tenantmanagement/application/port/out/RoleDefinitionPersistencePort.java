package com.nexa.api.tenantmanagement.application.port.out;

import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;

import java.util.List;
import java.util.Optional;

/** Forward-compatible persistence boundary; the primary migration owns its implementation. */
public interface RoleDefinitionPersistencePort {
	List<RoleDefinition> findForScope(TenantId tenantId, WorkspaceId workspaceId);
	Optional<RoleDefinition> findById(RoleDefinitionId id);
	boolean existsCode(TenantId tenantId, WorkspaceId workspaceId, String code, RoleDefinitionId excluding);
	RoleDefinition create(RoleDefinition definition);
	int update(RoleDefinition definition, long expectedVersion);
	int activeAssignmentCount(RoleDefinitionId id);
}
