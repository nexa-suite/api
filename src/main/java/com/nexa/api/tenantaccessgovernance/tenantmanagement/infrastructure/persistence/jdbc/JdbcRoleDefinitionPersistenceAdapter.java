package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.RoleDefinitionPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinition;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinitionStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.RoleDefinitionType;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.tenant.roles", name = "persistence-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcRoleDefinitionPersistenceAdapter implements RoleDefinitionPersistencePort {
	private static final String SELECT = "select r.id,r.tenant_id,r.workspace_id,r.role_type,r.code,r.name,r.description,r.status,r.created_by_membership_id,r.created_at,r.updated_at,r.version,coalesce(array_agg(rp.permission_key) filter (where rp.permission_key is not null), array[]::varchar[]) from tenant_management.role_definition r left join tenant_management.role_permission rp on rp.role_id=r.id";
	private final JdbcTemplate jdbc;

	public JdbcRoleDefinitionPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public List<RoleDefinition> findForScope(TenantId tenantId, WorkspaceId workspaceId) {
		return jdbc.query(SELECT + " where (r.tenant_id is null or (r.tenant_id=? and (r.workspace_id is null or r.workspace_id=?))) group by r.id order by r.code",
				(rs, row) -> restore(rs), tenantId.value(), workspaceId.value());
	}

	@Override
	public Optional<RoleDefinition> findById(RoleDefinitionId id) {
		return jdbc.query(SELECT + " where r.id=? group by r.id",
				rs -> rs.next() ? Optional.of(restore(rs)) : Optional.empty(), id.value());
	}

	@Override
	public boolean existsCode(TenantId tenantId, WorkspaceId workspaceId, String code, RoleDefinitionId excluding) {
		String sql = "select exists(select 1 from tenant_management.role_definition where tenant_id=? and (workspace_id is null or workspace_id=?) and lower(code)=lower(?)";
		if (excluding != null) sql += " and id<>?";
		sql += ")";
		return excluding == null
				? Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, tenantId.value(), workspaceId.value(), code))
				: Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, tenantId.value(), workspaceId.value(), code, excluding.value()));
	}

	@Override
	@Transactional
	public RoleDefinition create(RoleDefinition definition) {
		jdbc.update("insert into tenant_management.role_definition (id,tenant_id,workspace_id,code,name,description,role_type,status,created_by_membership_id,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?)",
				definition.id().value(), nullable(definition.tenantId()), nullable(definition.workspaceId()), definition.code(), definition.name(),
				definition.description(), definition.type().name(), definition.status().name(), definition.createdBy() == null ? null : definition.createdBy().value(),
				Timestamp.from(definition.createdAt()), Timestamp.from(definition.updatedAt()), definition.version());
		insertPermissions(definition);
		return definition;
	}

	@Override
	@Transactional
	public int update(RoleDefinition definition, long expectedVersion) {
		int updated = jdbc.update("update tenant_management.role_definition set name=?,description=?,status=?,updated_at=?,version=? where id=? and tenant_id=? and version=?",
				definition.name(), definition.description(), definition.status().name(), Timestamp.from(definition.updatedAt()), definition.version(),
				definition.id().value(), definition.tenantId().value(), expectedVersion);
		if (updated != 1) return updated;
		jdbc.update("delete from tenant_management.role_permission where role_id=?", definition.id().value());
		insertPermissions(definition);
		return updated;
	}

	@Override
	public int activeAssignmentCount(RoleDefinitionId id) {
		Integer count = jdbc.queryForObject("select count(*) from tenant_management.membership_role_definition m join tenant_management.workspace_membership w on w.id=m.membership_id where m.role_id=? and w.status='ACTIVE'",
				Integer.class, id.value());
		return count == null ? 0 : count;
	}

	private void insertPermissions(RoleDefinition definition) {
		for (PermissionKey permission : definition.permissions()) {
			jdbc.update("insert into tenant_management.role_permission (role_id,permission_key) values (?,?) on conflict (role_id,permission_key) do nothing",
					definition.id().value(), permission.code());
		}
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
	private static Object nullable(TenantId value) { return value == null ? null : value.value(); }
	private static Object nullable(WorkspaceId value) { return value == null ? null : value.value(); }
}
