package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.AuthorizationResolutionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.AuthorizationResolutionRequest;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccessPolicyAdapter implements AccessPolicyPort {
	private final JdbcTemplate jdbc;
	private final AuthorizationResolutionPort authorization;

	public JdbcAccessPolicyAdapter(JdbcTemplate jdbc) {
		this(jdbc, request -> EffectiveAuthorization.fixed(request.fixedRoles(), request.authorizationVersion()));
	}

	@Autowired
	public JdbcAccessPolicyAdapter(JdbcTemplate jdbc, AuthorizationResolutionPort authorization) {
		this.jdbc = jdbc;
		this.authorization = authorization;
	}

	@Override
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, ClientSurface surface) {
		return Optional.empty();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, String workspaceSlug, ClientSurface surface) {
		if (workspaceSlug == null || workspaceSlug.isBlank()) return Optional.empty();
		final UUID userId;
		try {
			userId = UUID.fromString(userAccountId.value());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		jdbc.queryForObject("select set_config('app.workspace_lookup_slug', ?, true)", String.class, workspaceSlug.toLowerCase(Locale.ROOT));
		String sql = "select m.id membership_id, m.membership_type, m.status membership_status, m.version authorization_version, "
				+ "w.id workspace_id, w.slug workspace_slug, w.name workspace_name, w.status workspace_status, "
				+ "t.id tenant_id, t.slug tenant_slug, t.name tenant_name, t.status tenant_status, "
				+ "u.display_name, u.preferred_language "
				+ "from tenant_management.workspace_membership m "
				+ "join tenant_management.workspace w on w.id = m.workspace_id "
				+ "join tenant_management.tenant t on t.id = w.tenant_id "
				+ "join iam.user_account u on u.id = m.user_id "
				+ "where m.user_id = ? and lower(w.slug) = ?";
		try {
			return jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Optional<AccessPolicy>>) rs -> resolveExactlyOne(rs, userId, surface), userId, workspaceSlug.toLowerCase(Locale.ROOT));
		} finally {
			jdbc.queryForObject("select set_config('app.workspace_lookup_slug', '', true)", String.class);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<AccessPolicy> findForMembership(UserAccountId userAccountId, String membershipId, ClientSurface surface) {
		if (membershipId == null || membershipId.isBlank()) return Optional.empty();
		final UUID userId;
		final UUID membership;
		try {
			userId = UUID.fromString(userAccountId.value());
			membership = UUID.fromString(membershipId);
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		jdbc.queryForObject("select set_config('app.workspace_membership_lookup_id', ?, true) || "
				+ "set_config('app.workspace_membership_lookup_user_id', ?, true)", String.class,
				membership.toString(), userId.toString());
		try {
			String sql = "select m.id membership_id, m.membership_type, m.status membership_status, m.version authorization_version, "
					+ "w.id workspace_id, w.slug workspace_slug, w.name workspace_name, w.status workspace_status, "
					+ "t.id tenant_id, t.slug tenant_slug, t.name tenant_name, t.status tenant_status, "
					+ "u.display_name, u.preferred_language "
					+ "from tenant_management.workspace_membership m "
					+ "join tenant_management.workspace w on w.id = m.workspace_id "
					+ "join tenant_management.tenant t on t.id = w.tenant_id "
					+ "join iam.user_account u on u.id = m.user_id "
					+ "where m.user_id = ? and m.id = ?";
			return jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<Optional<AccessPolicy>>) rs -> resolveExactlyOne(rs, userId, surface), userId, membership);
		} finally {
			jdbc.queryForObject("select set_config('app.workspace_membership_lookup_id', '', true) || "
					+ "set_config('app.workspace_membership_lookup_user_id', '', true)", String.class);
		}
	}

	@Override
	@Transactional(readOnly = true)
	public List<AccessPolicy> findAllFor(UserAccountId userAccountId, ClientSurface surface) {
		final UUID userId;
		try {
			userId = UUID.fromString(userAccountId.value());
		} catch (IllegalArgumentException exception) {
			return List.of();
		}
		String sql = "select m.id membership_id, m.membership_type, m.status membership_status, m.version authorization_version, "
				+ "w.id workspace_id, w.slug workspace_slug, w.name workspace_name, w.status workspace_status, "
				+ "t.id tenant_id, t.slug tenant_slug, t.name tenant_name, t.status tenant_status, "
				+ "u.display_name, u.preferred_language "
				+ "from tenant_management.workspace_membership m "
				+ "join tenant_management.workspace w on w.id = m.workspace_id "
				+ "join tenant_management.tenant t on t.id = w.tenant_id "
				+ "join iam.user_account u on u.id = m.user_id "
				+ "where m.user_id = ? order by m.id";
		return jdbc.query(sql, (org.springframework.jdbc.core.ResultSetExtractor<List<AccessPolicy>>) rs -> {
			List<AccessPolicy> eligible = new ArrayList<>();
			while (rs.next()) {
				if (!"ACTIVE".equals(rs.getString("membership_status"))
						|| !"ACTIVE".equals(rs.getString("workspace_status"))
						|| !"ACTIVE".equals(rs.getString("tenant_status"))) continue;
				bindResolvedScope(rs);
				AccessPolicy candidate = resolve(rs, userId, surface);
				if (candidate != null) eligible.add(candidate);
			}
			return orderAccessContexts(eligible);
		}, userId);
	}

	static List<AccessPolicy> orderAccessContexts(List<AccessPolicy> contexts) {
		return contexts.stream()
				.sorted(Comparator.comparing(AccessPolicy::tenantName)
						.thenComparing(AccessPolicy::workspaceName)
						.thenComparing(AccessPolicy::membershipId))
				.toList();
	}

	private Optional<AccessPolicy> resolveExactlyOne(java.sql.ResultSet rs, UUID userId, ClientSurface surface) throws java.sql.SQLException {
		AccessPolicy resolved = null;
		while (rs.next()) {
			if (!"ACTIVE".equals(rs.getString("membership_status"))
					|| !"ACTIVE".equals(rs.getString("workspace_status"))
					|| !"ACTIVE".equals(rs.getString("tenant_status"))) continue;
			bindResolvedScope(rs);
			AccessPolicy candidate = resolve(rs, userId, surface);
			if (candidate == null) continue;
			if (resolved != null) return Optional.empty();
			resolved = candidate;
		}
		return Optional.ofNullable(resolved);
	}

	private void bindResolvedScope(java.sql.ResultSet rs) throws java.sql.SQLException {
		jdbc.queryForObject("select set_config('app.current_tenant_id', ?, true) || "
				+ "set_config('app.current_workspace_id', ?, true)", String.class,
			rs.getObject("tenant_id", UUID.class).toString(), rs.getObject("workspace_id", UUID.class).toString());
	}

	private AccessPolicy resolve(java.sql.ResultSet rs, UUID userId, ClientSurface surface) throws java.sql.SQLException {
		Set<MembershipRole> roles = roles(rs.getObject("membership_id", UUID.class), rs.getString("membership_type"));
		Surface requestedSurface = Surface.valueOf(surface.name());
		MembershipId membershipId = new MembershipId(rs.getObject("membership_id", UUID.class).toString());
		UserId memberUserId = new UserId(userId);
		TenantId memberTenantId = new TenantId(rs.getObject("tenant_id", UUID.class).toString());
		WorkspaceId memberWorkspaceId = new WorkspaceId(rs.getObject("workspace_id", UUID.class).toString());
		EffectiveAuthorization effective;
		try {
			effective = authorization.resolve(new AuthorizationResolutionRequest(membershipId, memberUserId,
					memberTenantId, memberWorkspaceId, rs.getString("membership_type"), roles, rs.getLong("authorization_version")));
		} catch (RuntimeException exception) {
			return null;
		}
		if (!effective.allowsSurface(requestedSurface)) return null;
		return new AccessPolicy(surface, effective.roleCodes(), effective.permissionCodes(),
				rs.getObject("tenant_id", UUID.class).toString(), rs.getString("tenant_slug"),
				rs.getObject("workspace_id", UUID.class).toString(), rs.getString("workspace_slug"),
				rs.getObject("membership_id", UUID.class).toString(), rs.getString("display_name"),
				rs.getString("preferred_language"), effective.authorizationVersion(), effective.roleDefinitionIds(),
				rs.getString("tenant_name"), rs.getString("workspace_name"));
	}

	private Set<MembershipRole> roles(UUID membershipId, String membershipType) {
		if ("BUYER".equals(membershipType)) return Set.of(MembershipRole.BUYER);
		return jdbc.query("select r.code from tenant_management.membership_role_definition a "
				+ "join tenant_management.role_definition r on r.id=a.role_id "
				+ "where a.membership_id=? and r.tenant_id is null and r.status='ACTIVE'",
				(rs, row) -> rs.getString("code"), membershipId).stream()
				.map(JdbcAccessPolicyAdapter::builtInRoleOrNull)
				.filter(java.util.Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static MembershipRole builtInRoleOrNull(String code) {
		try { return MembershipRole.from(code); }
		catch (RuntimeException ignored) { return null; }
	}
}
