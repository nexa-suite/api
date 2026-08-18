package com.nexa.api.tenantmanagement.application.service;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.application.model.RoleDefinitionModels.CreateCommand;
import com.nexa.api.tenantmanagement.application.model.RoleDefinitionModels.UpdateCommand;
import com.nexa.api.tenantmanagement.application.port.in.RoleDefinitionUseCase;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantmanagement.domain.model.access.AssignableRolePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantmanagement.domain.model.access.RoleCatalog;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinitionType;
import com.nexa.api.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Application service for tenant-scoped role definitions. */
public final class RoleDefinitionService implements RoleDefinitionUseCase {
	private final RoleDefinitionPersistencePort definitions;
	private final AuthorizationVersionPort authorizationVersions;
	private final ChangeEventPersistencePort changes;
	private final Clock clock;

	public RoleDefinitionService(RoleDefinitionPersistencePort definitions, AuthorizationVersionPort authorizationVersions,
			ChangeEventPersistencePort changes, Clock clock) {
		this.definitions = Objects.requireNonNull(definitions, "Role definition persistence is required");
		this.authorizationVersions = Objects.requireNonNull(authorizationVersions, "Authorization version port is required");
		this.changes = Objects.requireNonNull(changes, "Change event port is required");
		this.clock = Objects.requireNonNull(clock, "Clock is required");
	}

	@Override
	public List<RoleDefinition> list(CurrentAccessContext context) {
		requireRead(context);
		Map<RoleDefinitionId, RoleDefinition> values = new LinkedHashMap<>();
		for (MembershipRole role : MembershipRole.values()) values.put(role.definition().id(), role.definition());
		for (RoleDefinition definition : definitions.findForScope(context.tenantId(), context.workspaceId())) {
			if (inScope(context, definition)) values.put(definition.id(), definition);
		}
		return values.values().stream().sorted(Comparator.comparing(RoleDefinition::code)).toList();
	}

	@Override
	public RoleDefinition get(CurrentAccessContext context, String id) {
		requireRead(context);
		RoleDefinition definition = find(context, id);
		if (!inScope(context, definition)) throw new RoleDefinitionNotFoundException();
		return definition;
	}

	@Override
	public RoleDefinition create(CurrentAccessContext context, CreateCommand command) {
		AssignableRolePolicy.requireCanManageDefinitions(context.roleCodes());
		if (command == null) throw new IllegalArgumentException("Role definition command is required");
		WorkspaceId workspaceId = optionalWorkspace(context, command.workspaceId());
		var permissions = PermissionCatalog.requireAll(command.permissions());
		AssignableRolePolicy.requireWithinAssignableEnvelope(context.roleCodes(), permissions);
		if (definitions.existsCode(context.tenantId(), workspaceId, command.code(), null)) {
			throw new DuplicateRoleDefinitionException();
		}
		Instant now = clock.instant();
		RoleDefinition created = definitions.create(RoleDefinition.custom(context.tenantId(), workspaceId, command.code(),
				command.name(), command.description(), permissions, context.userId(), now));
		publish(context, created, "tenant.role-definition.created");
		return created;
	}

	@Override
	public RoleDefinition update(CurrentAccessContext context, String id, UpdateCommand command, long expectedVersion) {
		AssignableRolePolicy.requireCanManageDefinitions(context.roleCodes());
		RoleDefinition current = get(context, id);
		if (current.type() != RoleDefinitionType.CUSTOM) throw new ImmutableRoleDefinitionException();
		if (command == null) throw new IllegalArgumentException("Role definition command is required");
		var permissions = PermissionCatalog.requireAll(command.permissions());
		AssignableRolePolicy.requireWithinAssignableEnvelope(context.roleCodes(), permissions);
		try {
			current.update(command.name(), command.description(), permissions, expectedVersion, clock.instant());
		} catch (RoleDefinition.RoleDefinitionConcurrencyException exception) {
			throw new RoleDefinitionConcurrencyException();
		}
		if (definitions.update(current, expectedVersion) == 0) throw new RoleDefinitionConcurrencyException();
		publish(context, current, "tenant.role-definition.updated");
		return current;
	}

	@Override
	public RoleDefinition deactivate(CurrentAccessContext context, String id, long expectedVersion) {
		AssignableRolePolicy.requireCanManageDefinitions(context.roleCodes());
		RoleDefinition current = get(context, id);
		if (current.type() != RoleDefinitionType.CUSTOM) throw new ImmutableRoleDefinitionException();
		long activeAssignments = definitions.activeAssignmentCount(current.id());
		if (activeAssignments > 0) throw new ActiveRoleDefinitionAssignmentsException();
		try {
			current.deactivate(expectedVersion, activeAssignments, clock.instant());
		} catch (RoleDefinition.RoleDefinitionConcurrencyException exception) {
			throw new RoleDefinitionConcurrencyException();
		}
		if (definitions.update(current, expectedVersion) == 0) throw new RoleDefinitionConcurrencyException();
		publish(context, current, "tenant.role-definition.deactivated");
		return current;
	}

	private RoleDefinition find(CurrentAccessContext context, String rawId) {
		final RoleDefinitionId id;
		try { id = new RoleDefinitionId(rawId); }
		catch (RuntimeException exception) { throw new RoleDefinitionNotFoundException(); }
		for (MembershipRole role : MembershipRole.values()) if (role.definition().id().equals(id)) return role.definition();
		return definitions.findById(id).orElseThrow(RoleDefinitionNotFoundException::new);
	}

	private static boolean inScope(CurrentAccessContext context, RoleDefinition definition) {
		return definition.tenantId() == null || (definition.tenantId().equals(context.tenantId())
			&& (definition.workspaceId() == null || definition.workspaceId().equals(context.workspaceId())));
	}

	private static WorkspaceId optionalWorkspace(CurrentAccessContext context, String raw) {
		if (raw == null || raw.isBlank()) return null;
		WorkspaceId value = new WorkspaceId(raw);
		if (!value.equals(context.workspaceId())) throw new RoleDefinitionNotFoundException();
		return value;
	}

	private static void requireRead(CurrentAccessContext context) {
		AssignableRolePolicy.requireCanRead(context.roleCodes(), context.permissionCodes());
	}
	private void publish(CurrentAccessContext context, RoleDefinition definition, String eventType) {
		authorizationVersions.bump(context.tenantId(), context.workspaceId());
		changes.append(context.tenantId().toString(), context.workspaceId().toString(), null, "role-definition",
				definition.id().toString(), eventType, definition.status().name(), clock.instant().toEpochMilli(), false);
	}

	public static final class RoleDefinitionNotFoundException extends RuntimeException { }
	public static final class DuplicateRoleDefinitionException extends RuntimeException { }
	public static final class ImmutableRoleDefinitionException extends RuntimeException { }
	public static final class ActiveRoleDefinitionAssignmentsException extends RuntimeException { }
	public static final class RoleDefinitionConcurrencyException extends RuntimeException { }
}
