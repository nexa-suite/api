package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.in.ResolveCurrentAccessContextUseCase;
import org.springframework.security.access.AccessDeniedException;

import java.util.Objects;

/**
 * Rechecks membership and authorization through the central access use case before reading
 * the central Tenant database registry. This adapter must be wired to central-only storage.
 */
public final class JdbcTenantBusinessDatabaseAuthority implements TenantBusinessDatabaseAuthority {
	private final ResolveCurrentAccessContextUseCase centralAccessContexts;
	private final TenantBusinessDatabaseBindingRegistry centralBindings;

	public JdbcTenantBusinessDatabaseAuthority(ResolveCurrentAccessContextUseCase centralAccessContexts,
			TenantBusinessDatabaseBindingRegistry centralBindings) {
		this.centralAccessContexts = Objects.requireNonNull(centralAccessContexts,
				"Central access-context resolver is required");
		this.centralBindings = Objects.requireNonNull(centralBindings,
				"Central Tenant database binding registry is required");
	}

	@Override
	public TenantBusinessDatabaseBinding requireReadyBinding(CurrentAccessContext presented) {
		if (presented == null) throw new AccessDeniedException("A verified Tenant access context is required");

		CurrentAccessContext current = centralAccessContexts.resolve(new CurrentAccessRequest(
				presented.userId(), presented.tenantId(), presented.workspaceId(), presented.surface()));
		if (!sameAuthority(presented, current)) {
			throw new AccessDeniedException("The Tenant access context changed and must be resolved again");
		}

		return centralBindings.findReadyBinding(current.tenantId())
				.orElseThrow(() -> new TenantBusinessDatabaseUnavailableException(
						"No ready business database is provisioned for the verified Tenant"));
	}

	private static boolean sameAuthority(CurrentAccessContext presented, CurrentAccessContext current) {
		return presented.userId().equals(current.userId())
				&& presented.tenantId().equals(current.tenantId())
				&& presented.workspaceId().equals(current.workspaceId())
				&& presented.membershipId().equals(current.membershipId())
				&& presented.surface() == current.surface()
				&& presented.authorizationVersion() == current.authorizationVersion()
				&& presented.roleCodes().equals(current.roleCodes())
				&& presented.roleDefinitionIds().equals(current.roleDefinitionIds())
				&& presented.permissionCodes().equals(current.permissionCodes());
	}
}
