package com.nexa.api.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionPort;
import com.nexa.api.tenantmanagement.application.port.out.AuthorizationResolutionRequest;
import com.nexa.api.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinitionStatus;
import com.nexa.api.tenantmanagement.domain.model.access.RoleDefinitionType;
import com.nexa.api.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Resolves fixed and tenant-defined role permissions on every access-context build. */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcAuthorizationResolutionAdapter implements AuthorizationResolutionPort {
	private final JdbcTemplate jdbc;

	public JdbcAuthorizationResolutionAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public EffectiveAuthorization resolve(AuthorizationResolutionRequest request) {
		UUID membershipId = request.membershipId().value();
		UUID tenantId = request.tenantId().value();
		UUID workspaceId = request.workspaceId().value();
		List<RoleDefinition> definitions = jdbc.query("select r.id,r.tenant_id,r.workspace_id,r.role_type,r.code,r.name,r.description,r.status,r.created_by_membership_id,r.created_at,r.updated_at,r.version,coalesce(array_agg(rp.permission_key) filter (where rp.permission_key is not null), array[]::varchar[]) from tenant_management.membership_role_definition a join tenant_management.role_definition r on r.id=a.role_id left join tenant_management.role_permission rp on rp.role_id=r.id where a.membership_id=? and a.tenant_id=? and a.workspace_id=? and r.status='ACTIVE' group by r.id",
				(rs, row) -> restore(rs), membershipId, tenantId, workspaceId);
		/* Membership version tracks lifecycle/ETag writes; it is not an
		 * authorization version. Only an explicit authorization-state row may
		 * invalidate an access snapshot. */
		Long version = jdbc.queryForObject("select coalesce((select authorization_version from tenant_management.membership_authorization_state where membership_id=?),0)", Long.class,
				membershipId);
		return EffectiveAuthorization.of(definitions, request.fixedRoles(), version == null ? 0 : version);
	}

	private static RoleDefinition restore(ResultSet rs) throws SQLException {
		UUID tenant = uuid(rs.getObject(2));
		UUID workspace = uuid(rs.getObject(3));
		UUID createdBy = uuid(rs.getObject(9));
		return RoleDefinition.restore(new RoleDefinitionId(rs.getObject(1, UUID.class)), tenant == null ? null : new TenantId(tenant.toString()),
				workspace == null ? null : new WorkspaceId(workspace.toString()), RoleDefinitionType.valueOf(rs.getString(4)), rs.getString(5),
				rs.getString(6), rs.getString(7), permissions(rs.getArray(13)), RoleDefinitionStatus.valueOf(rs.getString(8)),
				createdBy == null ? null : new UserId(createdBy.toString()), rs.getTimestamp(10).toInstant(), rs.getTimestamp(11).toInstant(), rs.getLong(12));
	}

	private static Set<PermissionKey> permissions(Array array) throws SQLException {
		if (array == null) return Set.of();
		Object value = array.getArray();
		if (!(value instanceof String[] codes)) return Set.of();
		return Arrays.stream(codes).map(PermissionKey::fromCodeOrNull).filter(java.util.Objects::nonNull).collect(Collectors.toUnmodifiableSet());
	}
	private static UUID uuid(Object value) { return value instanceof UUID uuid ? uuid : value == null ? null : UUID.fromString(value.toString()); }
}
