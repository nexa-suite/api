package com.nexa.api.bootstrap;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Component
@Profile("local")
@Conditional(LocalBootstrapEnabledCondition.class)
public class LocalDevelopmentBootstrap {
	private final JdbcTemplate jdbc;
	private final BCryptPasswordEncoder encoder;
	private final org.springframework.core.env.Environment environment;
	private final Clock clock;

	public LocalDevelopmentBootstrap(JdbcTemplate jdbc, org.springframework.core.env.Environment environment, Clock clock) {
		this.jdbc = jdbc;
		this.environment = environment;
		this.encoder = new BCryptPasswordEncoder(environment.getProperty("nexa.security.bcrypt-strength", Integer.class, 12));
		this.clock = clock;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void seed() {
		Instant now = clock.instant();
		UUID tenantId = tenant(now);
		UUID workspaceId = workspace(tenantId, now);
		List<UserSeed> users = List.of(
				new UserSeed("NEXA_DEV_OWNER_EMAIL", "NEXA_DEV_OWNER_PASSWORD", "COMPANY_OWNER"),
				new UserSeed("NEXA_DEV_SALES_EMAIL", "NEXA_DEV_SALES_PASSWORD", "SALES"),
				new UserSeed("NEXA_DEV_WAREHOUSE_EMAIL", "NEXA_DEV_WAREHOUSE_PASSWORD", "WAREHOUSE"),
				new UserSeed("NEXA_DEV_LOGISTICS_EMAIL", "NEXA_DEV_LOGISTICS_PASSWORD", "LOGISTICS"),
				new UserSeed("NEXA_DEV_BUYER_EMAIL", "NEXA_DEV_BUYER_PASSWORD", "BUYER"));
		for (UserSeed user : users) {
			UUID userId = user(user, now);
			jdbc.update("insert into tenant_management.workspace_membership "
					+ "(id, workspace_id, user_id, role, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0) "
					+ "on conflict (workspace_id, user_id) do update set role = excluded.role, status = 'ACTIVE', updated_at = excluded.updated_at",
					UUID.randomUUID(), workspaceId, userId, user.role(), timestamp(now), timestamp(now));
		}
	}

	private UUID tenant(Instant now) {
		String slug = required("NEXA_DEV_TENANT_SLUG").toLowerCase(java.util.Locale.ROOT);
		String name = required("NEXA_DEV_TENANT_NAME");
		List<UUID> existing = jdbc.query("select id from tenant_management.tenant where slug = ?", (rs, row) -> rs.getObject(1, UUID.class), slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = UUID.randomUUID();
		jdbc.update("insert into tenant_management.tenant (id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, 'ACTIVE', ?, ?, 0)", id, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID workspace(UUID tenantId, Instant now) {
		String slug = required("NEXA_DEV_WORKSPACE_SLUG").toLowerCase(java.util.Locale.ROOT);
		String name = required("NEXA_DEV_WORKSPACE_NAME");
		List<UUID> existing = jdbc.query("select id from tenant_management.workspace where tenant_id = ? and slug = ?",
				(rs, row) -> rs.getObject(1, UUID.class), tenantId, slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = UUID.randomUUID();
		jdbc.update("insert into tenant_management.workspace (id, tenant_id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
				id, tenantId, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID user(UserSeed seed, Instant now) {
		String email = required(seed.emailKey()).toLowerCase(java.util.Locale.ROOT);
		String password = required(seed.passwordKey());
		List<UUID> existing = jdbc.query("select id from iam.user_account where normalized_email = ?", (rs, row) -> rs.getObject(1, UUID.class), email);
		UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.get(0);
		if (existing.isEmpty()) {
			jdbc.update("insert into iam.user_account (id, email, normalized_email, username, normalized_username, display_name, preferred_language, status, created_at, updated_at, version) values (?, ?, ?, ?, ?, ?, 'es', 'ACTIVE', ?, ?, 0)",
					id, email, email, email, email, displayName(email), timestamp(now), timestamp(now));
		}
		jdbc.update("insert into iam.password_credential (user_id, password_hash, algorithm, changed_at) values (?, ?, 'bcrypt', ?) "
				+ "on conflict (user_id) do update set password_hash = excluded.password_hash, algorithm = excluded.algorithm, changed_at = excluded.changed_at",
				id, encoder.encode(password), timestamp(now));
		return id;
	}

	private String required(String key) {
		String value = environment.getProperty(key);
		if (value == null || value.isBlank()) throw new IllegalStateException("Missing local bootstrap variable " + key);
		return value.trim();
	}

	private static String displayName(String email) {
		String localPart = email.substring(0, email.indexOf('@')).replace('.', ' ').replace('_', ' ');
		return java.util.Arrays.stream(localPart.split("\\s+"))
				.filter(value -> !value.isBlank())
				.map(value -> Character.toUpperCase(value.charAt(0)) + value.substring(1))
				.collect(java.util.stream.Collectors.joining(" "));
	}

	private static java.sql.Timestamp timestamp(Instant instant) {
		return java.sql.Timestamp.from(instant);
	}

	private record UserSeed(String emailKey, String passwordKey, String role) {}
}
