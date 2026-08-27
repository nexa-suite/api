package com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Aggregate root for a tenant-defined authorization profile. */
public final class RoleDefinition {
	private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
	private final RoleDefinitionId id;
	private final TenantId tenantId;
	private final WorkspaceId workspaceId;
	private final RoleDefinitionType type;
	private final String code;
	private String name;
	private String description;
	private Set<PermissionKey> permissions;
	private RoleDefinitionStatus status;
	private final UserId createdBy;
	private final Instant createdAt;
	private Instant updatedAt;
	private long version;

	private RoleDefinition(RoleDefinitionId id, TenantId tenantId, WorkspaceId workspaceId, RoleDefinitionType type,
			String code, String name, String description, Set<PermissionKey> permissions, RoleDefinitionStatus status,
			UserId createdBy, Instant createdAt, Instant updatedAt, long version) {
		this.id = Objects.requireNonNull(id, "Role definition id is required");
		this.tenantId = tenantId;
		this.workspaceId = workspaceId;
		this.type = Objects.requireNonNull(type, "Role definition type is required");
		this.code = normalizeCode(code, type);
		this.name = requiredText(name, "Role definition name", 160);
		this.description = description == null ? "" : description.trim();
		if (this.description.length() > 500) throw new IllegalArgumentException("Role definition description is too long");
		this.permissions = validatePermissions(permissions);
		this.status = Objects.requireNonNull(status, "Role definition status is required");
		this.createdBy = createdBy;
		this.createdAt = Objects.requireNonNull(createdAt, "Role definition createdAt is required");
		this.updatedAt = Objects.requireNonNull(updatedAt, "Role definition updatedAt is required");
		if (version < 0) throw new IllegalArgumentException("Role definition version cannot be negative");
		this.version = version;
		if (type != RoleDefinitionType.CUSTOM && (tenantId != null || workspaceId != null)) {
			throw new IllegalArgumentException("System role definitions cannot be tenant scoped");
		}
		if (type == RoleDefinitionType.CUSTOM && tenantId == null) {
			throw new IllegalArgumentException("Custom role tenant is required");
		}
	}

	public static RoleDefinition custom(TenantId tenantId, WorkspaceId workspaceId, String code, String name,
			String description, Set<PermissionKey> permissions, UserId createdBy, Instant now) {
		return new RoleDefinition(RoleDefinitionId.random(), tenantId, workspaceId, RoleDefinitionType.CUSTOM, code, name,
				description, permissions, RoleDefinitionStatus.ACTIVE, createdBy, now, now, 0);
	}

	public static RoleDefinition systemReserved(MembershipRole role, Instant now) {
		if (role != MembershipRole.TENANT_ADMIN && role != MembershipRole.COMPANY_OWNER) {
			throw new IllegalArgumentException("Only technical and company owner roles are reserved");
		}
		return system(role, RoleDefinitionType.SYSTEM_RESERVED, now);
	}

	public static RoleDefinition systemTemplate(MembershipRole role, Instant now) {
		if (role == MembershipRole.TENANT_ADMIN || role == MembershipRole.COMPANY_OWNER) {
			throw new IllegalArgumentException("Role is not a system template");
		}
		return system(role, RoleDefinitionType.SYSTEM_TEMPLATE, now);
	}

	private static RoleDefinition system(MembershipRole role, RoleDefinitionType type, Instant now) {
		String code = role.name().toLowerCase(java.util.Locale.ROOT);
		return new RoleDefinition(RoleDefinitionId.system(role.name()), null, null, type, code, displayName(role),
			"Nexa system role", PermissionCatalog.forBuiltInRole(role), RoleDefinitionStatus.ACTIVE, null, now, now, 0);
	}

	public static RoleDefinition restore(RoleDefinitionId id, TenantId tenantId, WorkspaceId workspaceId,
			RoleDefinitionType type, String code, String name, String description, Set<PermissionKey> permissions,
			RoleDefinitionStatus status, UserId createdBy, Instant createdAt, Instant updatedAt, long version) {
		return new RoleDefinition(id, tenantId, workspaceId, type, code, name, description, permissions, status, createdBy,
			createdAt, updatedAt, version);
	}

	public void update(String name, String description, Set<PermissionKey> permissions, long expectedVersion, Instant now) {
		checkVersion(expectedVersion);
		if (type != RoleDefinitionType.CUSTOM) throw new AccessPolicyViolation("System role definitions are immutable");
		if (status != RoleDefinitionStatus.ACTIVE) throw new AccessPolicyViolation("Inactive role definitions cannot be edited");
		this.name = requiredText(name, "Role definition name", 160);
		this.description = description == null ? "" : description.trim();
		if (this.description.length() > 500) throw new IllegalArgumentException("Role definition description is too long");
		this.permissions = validatePermissions(permissions);
		this.updatedAt = Objects.requireNonNull(now, "Update time is required");
		this.version++;
	}

	public void deactivate(long expectedVersion, long activeAssignments, Instant now) {
		checkVersion(expectedVersion);
		if (type != RoleDefinitionType.CUSTOM) throw new AccessPolicyViolation("System role definitions are immutable");
		if (status == RoleDefinitionStatus.INACTIVE) return;
		if (activeAssignments > 0) throw new AccessPolicyViolation("Active memberships must be reassigned before role deactivation");
		status = RoleDefinitionStatus.INACTIVE;
		updatedAt = Objects.requireNonNull(now, "Update time is required");
		version++;
	}

	private void checkVersion(long expectedVersion) {
		if (expectedVersion != version) throw new RoleDefinitionConcurrencyException();
	}

	private static Set<PermissionKey> validatePermissions(Set<PermissionKey> values) {
		if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("At least one typed permission is required");
		}
		return Set.copyOf(values);
	}

	private static String normalizeCode(String value, RoleDefinitionType type) {
		String normalized = requiredText(value, "Role definition code", 64).toLowerCase(java.util.Locale.ROOT);
		if (!CODE.matcher(normalized).matches()) throw new IllegalArgumentException("Role definition code is invalid");
		if (type == RoleDefinitionType.CUSTOM) {
			if (PermissionCatalog.isKnown(normalized)) throw new IllegalArgumentException("Role definition code conflicts with a permission key");
			if (java.util.Arrays.stream(MembershipRole.values()).anyMatch(role -> role.name().equalsIgnoreCase(normalized))) {
				throw new IllegalArgumentException("Role definition code conflicts with a system role");
			}
		}
		return normalized;
	}

	private static String requiredText(String value, String label, int max) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
		String normalized = value.trim();
		if (normalized.length() > max) throw new IllegalArgumentException(label + " is too long");
		return normalized;
	}

	private static String displayName(MembershipRole role) {
		return java.util.Arrays.stream(role.name().split("_")).map(word -> word.substring(0, 1) + word.substring(1).toLowerCase(java.util.Locale.ROOT)).reduce((a, b) -> a + " " + b).orElse(role.name());
	}

	public RoleDefinitionId id() { return id; }
	public TenantId tenantId() { return tenantId; }
	public WorkspaceId workspaceId() { return workspaceId; }
	public RoleDefinitionType type() { return type; }
	public String code() { return code; }
	public String name() { return name; }
	public String description() { return description; }
	public Set<PermissionKey> permissions() { return permissions; }
	public RoleDefinitionStatus status() { return status; }
	public UserId createdBy() { return createdBy; }
	public Instant createdAt() { return createdAt; }
	public Instant updatedAt() { return updatedAt; }
	public long version() { return version; }
	public boolean isActive() { return status == RoleDefinitionStatus.ACTIVE; }
	public boolean isSystemManaged() { return type != RoleDefinitionType.CUSTOM; }

	public static final class RoleDefinitionConcurrencyException extends RuntimeException {
		public RoleDefinitionConcurrencyException() { super("Role definition version is stale"); }
	}
}
