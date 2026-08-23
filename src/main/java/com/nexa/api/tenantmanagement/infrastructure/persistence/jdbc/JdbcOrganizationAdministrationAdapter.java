package com.nexa.api.tenantmanagement.infrastructure.persistence.jdbc;

import org.springframework.dao.DuplicateKeyException;

import com.nexa.api.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionCatalog;
import com.nexa.api.tenantmanagement.domain.model.identity.RoleDefinitionId;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Profile("!test")
public class JdbcOrganizationAdministrationAdapter implements OrganizationAdministrationPort {
	private static final String TENANT_ADMIN_GUARD = "(cast(? as boolean) or not exists (select 1 from tenant_management.workspace_membership current_member where current_member.id=m.id and current_member.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition current_definition join tenant_management.role_definition current_rd on current_rd.id=current_definition.role_id where current_definition.membership_id=current_member.id and current_rd.code='tenant_admin' and current_rd.status='ACTIVE')) or exists (select 1 from tenant_management.workspace_membership other where other.workspace_id=m.workspace_id and other.id<>m.id and other.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition other_definition join tenant_management.role_definition other_rd on other_rd.id=other_definition.role_id where other_definition.membership_id=other.id and other_rd.code='tenant_admin' and other_rd.status='ACTIVE')))";
	private final JdbcTemplate jdbc;
	public JdbcOrganizationAdministrationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override public Optional<OrganizationSummary> findOrganization(String tenantId, String workspaceId) {
		return jdbc.query("select t.id,t.name,t.slug,t.status,t.version,w.id,w.name from tenant_management.tenant t join tenant_management.workspace w on w.tenant_id=t.id where t.id=? and w.id=?", rs -> rs.next() ? Optional.of(new OrganizationSummary(rs.getObject(1).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(6).toString(),rs.getString(7),rs.getLong(5))) : Optional.empty(), uuid(tenantId), uuid(workspaceId));
	}
	@Override public List<WorkspaceSummary> findWorkspaces(String tenantId) { return jdbc.query("select id,tenant_id,name,slug,status,version from tenant_management.workspace where tenant_id=? order by name", (rs,n)->new WorkspaceSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)), uuid(tenantId)); }
	@Override public boolean tenantHasWorkspace(String tenantId) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenant_management.workspace where tenant_id=?)", Boolean.class, uuid(tenantId))); }
	@Override public Optional<WorkspaceSummary> findWorkspace(String tenantId, String workspaceId) { return jdbc.query("select id,tenant_id,name,slug,status,version from tenant_management.workspace where tenant_id=? and id=?", rs -> rs.next()?Optional.of(new WorkspaceSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6))):Optional.empty(),uuid(tenantId),uuid(workspaceId)); }
	@Override public List<WorkspaceMembershipSummary> findMemberships(String tenantId, String workspaceId) { return jdbc.query(membershipSql() + " where w.tenant_id=? and m.workspace_id=? group by m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.membership_type,m.status,m.version order by u.display_name", (org.springframework.jdbc.core.RowMapper<WorkspaceMembershipSummary>) this::membership, uuid(tenantId), uuid(workspaceId)); }
	@Override public List<WorkspaceMembershipSummary> findMemberships(String tenantId) { return jdbc.query(membershipSql() + " where w.tenant_id=? group by m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.membership_type,m.status,m.version order by u.display_name", (org.springframework.jdbc.core.RowMapper<WorkspaceMembershipSummary>) this::membership, uuid(tenantId)); }
	@Override public Optional<WorkspaceMembershipSummary> findMembership(String tenantId, String membershipId) { return jdbc.query(membershipSql() + " where w.tenant_id=? and m.id=? group by m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.membership_type,m.status,m.version",rs -> rs.next()?Optional.of(membership(rs)):Optional.empty(),uuid(tenantId),uuid(membershipId)); }
	@Override public int createWorkspace(String tenantId, java.util.UUID workspaceId, String name, String slug, java.time.Instant createdAt) { try { return jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) values (?,?,?,?,'ACTIVE',?,?,0)", workspaceId, uuid(tenantId), name, slug, java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt)); } catch (DuplicateKeyException exception) { return 0; } }
	@Override public Optional<java.util.UUID> findWorkspaceIdempotent(String tenantId, String idempotencyKey, String requestHash) { return jdbc.query("select workspace_id from tenant_management.workspace_creation_idempotency where tenant_id=? and idempotency_key=? and request_hash=?", (rs, row) -> rs.getObject(1, java.util.UUID.class), uuid(tenantId), idempotencyKey, requestHash).stream().findFirst(); }
	@Override public boolean workspaceIdempotencyKeyHasDifferentPayload(String tenantId, String idempotencyKey, String requestHash) { return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from tenant_management.workspace_creation_idempotency where tenant_id=? and idempotency_key=? and request_hash<>?)", Boolean.class, uuid(tenantId), idempotencyKey, requestHash)); }
	@Override public int saveWorkspaceIdempotency(String tenantId, String idempotencyKey, String requestHash, java.util.UUID workspaceId) { return jdbc.update("insert into tenant_management.workspace_creation_idempotency (tenant_id,idempotency_key,request_hash,workspace_id,created_at) values (?,?,?,?,current_timestamp) on conflict (tenant_id,idempotency_key) do nothing", uuid(tenantId), idempotencyKey, requestHash, workspaceId); }
	@Override public void createWorkspaceMembership(String tenantId, String workspaceId, java.util.UUID userId, java.util.Set<String> roles, java.time.Instant createdAt) {
		java.util.UUID membershipId = java.util.UUID.randomUUID();
		jdbc.update("insert into tenant_management.workspace_membership (id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)", membershipId, uuid(workspaceId), userId, java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(createdAt));
		replaceCanonicalRoleDefinitions(uuid(tenantId), membershipId, roles);
	}
	@Override public int updateWorkspace(String tenantId,String workspaceId,String name,String slug,String status,long version){ return jdbc.update("update tenant_management.workspace set name=?,slug=?,status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and id=? and version=?",name,slug,status,uuid(tenantId),uuid(workspaceId),version); }
	@Override public int updateWorkspaceStatus(String tenantId,String workspaceId,String status,long version){ return jdbc.update("update tenant_management.workspace set status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and id=? and version=?",status,uuid(tenantId),uuid(workspaceId),version); }
	@Override public void lockTenant(String tenantId){ jdbc.queryForObject("select id from tenant_management.tenant where id=? for update", java.util.UUID.class, uuid(tenantId)); }
	@Override public int activeAdministrativeWorkspaceCount(String tenantId){ return jdbc.queryForObject("select count(*) from tenant_management.workspace w where w.tenant_id=? and w.status='ACTIVE' and exists (select 1 from tenant_management.workspace_membership m where m.workspace_id=w.id and m.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition a join tenant_management.role_definition rd on rd.id=a.role_id where a.membership_id=m.id and rd.code='tenant_admin' and rd.status='ACTIVE'))", Integer.class, uuid(tenantId)); }
	@Override public int activeOwnerCount(String workspaceId){ return jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m where m.workspace_id=? and m.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition a join tenant_management.role_definition rd on rd.id=a.role_id where a.membership_id=m.id and rd.code='company_owner' and rd.status='ACTIVE')",Integer.class,uuid(workspaceId)); }
	@Override public int activeCompanyOwnerCount(String tenantId){ return jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id where w.tenant_id=? and m.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition a join tenant_management.role_definition rd on rd.id=a.role_id where a.membership_id=m.id and rd.code='company_owner' and rd.status='ACTIVE')", Integer.class, uuid(tenantId)); }
	@Override public int activeTenantAdminCount(String workspaceId){ return jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m where m.workspace_id=? and m.status='ACTIVE' and exists (select 1 from tenant_management.membership_role_definition a join tenant_management.role_definition rd on rd.id=a.role_id where a.membership_id=m.id and rd.code='tenant_admin' and rd.status='ACTIVE')",Integer.class,uuid(workspaceId)); }
	@Override @Transactional public int updateRoles(String tenantId,String membershipId,Set<String> roles,long version){
		lockMembershipWorkspace(tenantId, membershipId);
		int updated = jdbc.update("update tenant_management.workspace_membership m set updated_at=current_timestamp,version=m.version+1 from tenant_management.workspace w where m.workspace_id=w.id and w.tenant_id=? and m.id=? and m.membership_type='INTERNAL' and m.version=? and " + TENANT_ADMIN_GUARD, uuid(tenantId),uuid(membershipId),version,roles.contains("TENANT_ADMIN"));
		if (updated == 0) return 0;
		replaceCanonicalRoleDefinitions(uuid(tenantId), uuid(membershipId), roles);
		bumpAuthorizationVersion(uuid(membershipId), uuid(tenantId));
		return updated;
	}
	@Override @Transactional public int updateRoleDefinitionAssignments(String tenantId, String membershipId, Set<String> roleDefinitionIds, long version) {
		lockMembershipWorkspace(tenantId, membershipId);
		int updated = jdbc.update("update tenant_management.workspace_membership m set updated_at=current_timestamp,version=m.version+1 from tenant_management.workspace w where m.workspace_id=w.id and w.tenant_id=? and m.id=? and m.membership_type='INTERNAL' and m.version=?",
				uuid(tenantId), uuid(membershipId), version);
		if (updated == 0) return 0;
		jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", uuid(membershipId));
		for (String roleDefinitionId : roleDefinitionIds) {
			jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) select m.id,w.tenant_id,m.workspace_id,?,current_timestamp from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id where m.id=? and w.tenant_id=?",
					uuid(roleDefinitionId), uuid(membershipId), uuid(tenantId));
		}
		bumpAuthorizationVersion(uuid(membershipId), uuid(tenantId));
		return updated;
	}
	@Override @Transactional public int updateStatus(String tenantId,String membershipId,String status,long version){
		lockMembershipWorkspace(tenantId, membershipId);
		return jdbc.update("update tenant_management.workspace_membership m set status=?,updated_at=current_timestamp,version=m.version+1 from tenant_management.workspace w where m.workspace_id=w.id and w.tenant_id=? and m.id=? and m.version=? and " + TENANT_ADMIN_GUARD,status,uuid(tenantId),uuid(membershipId),version,!"DISABLED".equals(status));
	}
	private void lockMembershipWorkspace(String tenantId,String membershipId){ jdbc.queryForObject("select w.id from tenant_management.workspace w join tenant_management.workspace_membership m on m.workspace_id=w.id where w.tenant_id=? and m.id=? for update",java.util.UUID.class,uuid(tenantId),uuid(membershipId)); }
	@Override public void appendMembershipEvent(String type,String tenantId,String workspaceId,String targetMembershipId,String actorMembershipId,String beforeRole,String beforeStatus,String afterRole,String afterStatus,String correlationId){ jdbc.update("insert into tenant_management.membership_admin_event (id,event_type,tenant_id,workspace_id,target_membership_id,actor_membership_id,before_role,before_status,after_role,after_status,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,current_timestamp)",java.util.UUID.randomUUID(),type,uuid(tenantId),uuid(workspaceId),uuid(targetMembershipId),uuid(actorMembershipId),beforeRole,beforeStatus,afterRole,afterStatus,correlationId == null ? "unknown" : correlationId); }
	private String membershipSql() { return "select m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.membership_type,m.status,m.version,case when m.membership_type='BUYER' then array['BUYER'] else coalesce((select array_agg(r.code order by r.code) from tenant_management.membership_role_definition a join tenant_management.role_definition r on r.id=a.role_id where a.membership_id=m.id and r.status='ACTIVE'), array[]::varchar[]) end as roles,coalesce((select array_agg(a.role_id::text order by a.role_id) from tenant_management.membership_role_definition a where a.membership_id=m.id), array[]::text[]) as role_definition_ids,coalesce((select array_agg(distinct p.permission_key order by p.permission_key) from tenant_management.membership_role_definition a join tenant_management.role_permission p on p.role_id=a.role_id where a.membership_id=m.id), array[]::text[]) as dynamic_permissions from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join iam.user_account u on u.id=m.user_id"; }
	private WorkspaceMembershipSummary membership(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return membership(rs); }
	private WorkspaceMembershipSummary membership(java.sql.ResultSet rs) throws java.sql.SQLException {
		Set<String> roles = stringArray(rs.getArray(9));
		roles = roles.stream().map(JdbcOrganizationAdministrationAdapter::apiRoleCode).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> roleIds = new LinkedHashSet<>(stringArray(rs.getArray(10)));
		Set<String> permissions = new LinkedHashSet<>(stringArray(rs.getArray(11)));
		return new WorkspaceMembershipSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getObject(3).toString(),rs.getString(4),rs.getString(5),rs.getString(7),rs.getLong(8),roles,roleIds,permissions);
	}
	private static String apiRoleCode(String code) { return java.util.Arrays.stream(MembershipRole.values()).filter(role -> role.name().equalsIgnoreCase(code)).map(Enum::name).findFirst().orElse(code); }
	private static Set<String> stringArray(java.sql.Array array) throws java.sql.SQLException {
		if (array == null) return Set.of();
		Object value = array.getArray();
		return value instanceof String[] values ? new LinkedHashSet<>(Arrays.asList(values)) : Set.of();
	}
	private void bumpAuthorizationVersion(java.util.UUID membershipId, java.util.UUID tenantId) {
		jdbc.update("insert into tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) select m.id,w.tenant_id,m.workspace_id,1,current_timestamp from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id where m.id=? and w.tenant_id=? on conflict (membership_id) do update set authorization_version=tenant_management.membership_authorization_state.authorization_version+1,updated_at=current_timestamp", membershipId, tenantId);
	}
	private void replaceCanonicalRoleDefinitions(java.util.UUID tenantId, java.util.UUID membershipId, Set<String> roles) {
		jdbc.update("delete from tenant_management.membership_role_definition where membership_id=?", membershipId);
		for (String role : roles) {
			jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) select m.id,w.tenant_id,m.workspace_id,r.id,current_timestamp from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join tenant_management.role_definition r on r.tenant_id is null and r.code=lower(?) where m.id=? and w.tenant_id=? on conflict (membership_id,role_id) do nothing", role, membershipId, tenantId);
		}
	}
	private static java.util.UUID uuid(String value) { return java.util.UUID.fromString(value); }
}
