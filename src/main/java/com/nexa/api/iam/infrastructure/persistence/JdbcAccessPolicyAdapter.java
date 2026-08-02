package com.nexa.api.iam.infrastructure.persistence;

import com.nexa.api.iam.application.model.AccessPolicy;
import com.nexa.api.iam.application.port.out.AccessPolicyPort;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.access.PermissionPolicy;
import com.nexa.api.tenantmanagement.domain.model.access.RoleSurfacePolicy;
import com.nexa.api.tenantmanagement.domain.model.access.Surface;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccessPolicyAdapter implements AccessPolicyPort {
	private final JdbcTemplate jdbc;

	public JdbcAccessPolicyAdapter(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, ClientSurface surface) {
		return Optional.empty();
	}

	@Override
	public Optional<AccessPolicy> findFor(UserAccountId userAccountId, String workspaceSlug, ClientSurface surface) {
		if (workspaceSlug == null || workspaceSlug.isBlank()) return Optional.empty();
		final UUID userId;
		try {
			userId = UUID.fromString(userAccountId.value());
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
		String sql = "select m.id membership_id, m.membership_type, m.status membership_status, "
				+ "w.id workspace_id, w.slug workspace_slug, w.status workspace_status, "
				+ "t.id tenant_id, t.slug tenant_slug, t.status tenant_status, "
				+ "u.display_name, u.preferred_language "
				+ "from tenant_management.workspace_membership m "
				+ "join tenant_management.workspace w on w.id = m.workspace_id "
				+ "join tenant_management.tenant t on t.id = w.tenant_id "
				+ "join iam.user_account u on u.id = m.user_id "
				+ "where m.user_id = ? and lower(w.slug) = ? limit 1";
		return jdbc.query(sql, rs -> {
			if (!rs.next() || !"ACTIVE".equals(rs.getString("membership_status"))) return Optional.empty();
			Set<MembershipRole> roles = roles(rs.getObject("membership_id", UUID.class), rs.getString("membership_type"));
			if (roles.isEmpty() || !"ACTIVE".equals(rs.getString("workspace_status"))
					|| !"ACTIVE".equals(rs.getString("tenant_status"))) return Optional.empty();
			Surface requestedSurface = Surface.valueOf(surface.name());
			if (!RoleSurfacePolicy.allows(roles, requestedSurface)) return Optional.empty();
			Set<String> permissions = PermissionPolicy.permissionsFor(roles).stream().map(Permission::code)
					.collect(Collectors.toUnmodifiableSet());
			Set<String> roleValues = roles.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());
			return Optional.of(new AccessPolicy(surface, roleValues, permissions,
					rs.getObject("tenant_id", UUID.class).toString(), rs.getString("tenant_slug"),
					rs.getObject("workspace_id", UUID.class).toString(), rs.getString("workspace_slug"),
					rs.getObject("membership_id", UUID.class).toString(), rs.getString("display_name"),
				rs.getString("preferred_language")));
		}, userId, workspaceSlug.toLowerCase(Locale.ROOT));
	}

	private Set<MembershipRole> roles(UUID membershipId, String membershipType) {
		if ("BUYER".equals(membershipType)) return Set.of(MembershipRole.BUYER);
		return jdbc.query("select role from tenant_management.membership_role_assignment where membership_id = ?",
				(rs, row) -> MembershipRole.from(rs.getString("role")), membershipId)
				.stream().collect(Collectors.toUnmodifiableSet());
	}
}
