package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.exception.RoleDefinitionPersistenceUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Keeps the API bootable before the primary integrates role-definition tables.
 * It exposes system roles and fails writes explicitly instead of returning 500
 * from an accidental missing-table query.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "persistence-enabled", havingValue = "false")
public final class EmptyRoleDefinitionPersistenceAdapter implements RoleDefinitionPersistencePort {
	@Override public List<RoleDefinition> findForScope(TenantId tenantId, WorkspaceId workspaceId) { return List.of(); }
	@Override public Optional<RoleDefinition> findById(RoleDefinitionId id) { return Optional.empty(); }
	@Override public boolean existsCode(TenantId tenantId, WorkspaceId workspaceId, String code, RoleDefinitionId excluding) { return false; }
	@Override public RoleDefinition create(RoleDefinition definition) { throw new RoleDefinitionPersistenceUnavailableException(); }
	@Override public int update(RoleDefinition definition, long expectedVersion) { throw new RoleDefinitionPersistenceUnavailableException(); }
	@Override public int activeAssignmentCount(RoleDefinitionId id) { throw new RoleDefinitionPersistenceUnavailableException(); }
}
