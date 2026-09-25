package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.AccessContextDiscoveryScopePort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** Installs the trusted identity GUC required by the V102 pre-context workspace policy. */
@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcAccessContextDiscoveryScopeAdapter implements AccessContextDiscoveryScopePort {
	private final JdbcTemplate jdbc;

	public JdbcAccessContextDiscoveryScopeAdapter(JdbcTemplate jdbc) {
		this.jdbc = Objects.requireNonNull(jdbc, "JDBC template is required");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void bindTrustedIdentity(UserAccountId userAccountId) {
		UUID identity = UUID.fromString(Objects.requireNonNull(userAccountId, "User account id is required").value());
		jdbc.queryForObject("select set_config('app.access_context_user_id', ?, true)", String.class, identity.toString());
	}
}
