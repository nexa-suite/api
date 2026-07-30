package com.nexa.api.tenantmanagement.infrastructure.persistence.jdbc;

import com.nexa.api.tenantmanagement.application.model.OrganizationSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceMembershipSummary;
import com.nexa.api.tenantmanagement.application.model.WorkspaceSummary;
import com.nexa.api.tenantmanagement.application.port.out.OrganizationAdministrationPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Profile("!test")
public class JdbcOrganizationAdministrationAdapter implements OrganizationAdministrationPort {
	private final JdbcTemplate jdbc;
	public JdbcOrganizationAdministrationAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override public Optional<OrganizationSummary> findOrganization(String tenantId, String workspaceId) {
		return jdbc.query("select t.id,t.name,t.slug,t.status,t.version,w.id,w.name from tenant_management.tenant t join tenant_management.workspace w on w.tenant_id=t.id where t.id=? and w.id=?", rs -> rs.next() ? Optional.of(new OrganizationSummary(rs.getObject(1).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getObject(6).toString(),rs.getString(7),rs.getLong(5))) : Optional.empty(), uuid(tenantId), uuid(workspaceId));
	}
	@Override public List<WorkspaceSummary> findWorkspaces(String tenantId) { return jdbc.query("select id,tenant_id,name,slug,status,version from tenant_management.workspace where tenant_id=? order by name", (rs,n)->new WorkspaceSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6)), uuid(tenantId)); }
	@Override public Optional<WorkspaceSummary> findWorkspace(String tenantId, String workspaceId) { return jdbc.query("select id,tenant_id,name,slug,status,version from tenant_management.workspace where tenant_id=? and id=?", rs -> rs.next()?Optional.of(new WorkspaceSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getString(3),rs.getString(4),rs.getString(5),rs.getLong(6))):Optional.empty(),uuid(tenantId),uuid(workspaceId)); }
	@Override public List<WorkspaceMembershipSummary> findMemberships(String tenantId, String workspaceId) { return jdbc.query("select m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.role,m.status,m.version from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join iam.user_account u on u.id=m.user_id where w.tenant_id=? and m.workspace_id=? order by u.display_name",(rs,n)->new WorkspaceMembershipSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getObject(3).toString(),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(8)),uuid(tenantId),uuid(workspaceId)); }
	@Override public Optional<WorkspaceMembershipSummary> findMembership(String tenantId, String membershipId) { return jdbc.query("select m.id,m.workspace_id,m.user_id,u.email,u.display_name,m.role,m.status,m.version from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join iam.user_account u on u.id=m.user_id where w.tenant_id=? and m.id=?",rs -> rs.next()?Optional.of(new WorkspaceMembershipSummary(rs.getObject(1).toString(),rs.getObject(2).toString(),rs.getObject(3).toString(),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getLong(8))):Optional.empty(),uuid(tenantId),uuid(membershipId)); }
	@Override public int updateWorkspace(String tenantId,String workspaceId,String name,String status,long version){ return jdbc.update("update tenant_management.workspace set name=?,status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and id=? and version=?",name,status,uuid(tenantId),uuid(workspaceId),version); }
	@Override public int activeOwnerCount(String workspaceId){ return jdbc.queryForObject("select count(*) from tenant_management.workspace_membership where workspace_id=? and role='COMPANY_OWNER' and status='ACTIVE'",Integer.class,uuid(workspaceId)); }
	@Override public int updateRole(String tenantId,String membershipId,String role,long version){ return jdbc.update("update tenant_management.workspace_membership m set role=?,updated_at=current_timestamp,version=m.version+1 from tenant_management.workspace w where m.workspace_id=w.id and w.tenant_id=? and m.id=? and m.version=?",role,uuid(tenantId),uuid(membershipId),version); }
	@Override public int updateStatus(String tenantId,String membershipId,String status,long version){ return jdbc.update("update tenant_management.workspace_membership m set status=?,updated_at=current_timestamp,version=m.version+1 from tenant_management.workspace w where m.workspace_id=w.id and w.tenant_id=? and m.id=? and m.version=?",status,uuid(tenantId),uuid(membershipId),version); }
	@Override public void appendMembershipEvent(String type,String tenantId,String workspaceId,String targetMembershipId,String actorMembershipId,String beforeRole,String beforeStatus,String afterRole,String afterStatus,String correlationId){ jdbc.update("insert into tenant_management.membership_admin_event (id,event_type,tenant_id,workspace_id,target_membership_id,actor_membership_id,before_role,before_status,after_role,after_status,correlation_id,occurred_at) values (?,?,?,?,?,?,?,?,?,?,?,current_timestamp)",java.util.UUID.randomUUID(),type,uuid(tenantId),uuid(workspaceId),uuid(targetMembershipId),uuid(actorMembershipId),beforeRole,beforeStatus,afterRole,afterStatus,correlationId == null ? "unknown" : correlationId); }
	private static java.util.UUID uuid(String value) { return java.util.UUID.fromString(value); }
}
