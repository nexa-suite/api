package com.nexa.api.bootstrap;

import com.nexa.api.sales.infrastructure.seed.ClientAccountSeedLoader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
@Profile("local")
@Conditional(LocalBootstrapEnabledCondition.class)
public class LocalDevelopmentBootstrap {
	private final JdbcTemplate jdbc;
	private final BCryptPasswordEncoder encoder;
	private final org.springframework.core.env.Environment environment;
	private final Clock clock;
	private final ClientAccountSeedLoader clientAccountSeedLoader;

	public LocalDevelopmentBootstrap(JdbcTemplate jdbc, org.springframework.core.env.Environment environment, Clock clock, ClientAccountSeedLoader clientAccountSeedLoader) {
		this.jdbc = jdbc;
		this.environment = environment;
		this.encoder = new BCryptPasswordEncoder(environment.getProperty("nexa.security.bcrypt-strength", Integer.class, 12));
		this.clock = clock;
		this.clientAccountSeedLoader = clientAccountSeedLoader;
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void seed() {
		Instant now = clock.instant();
		UUID tenantId = tenant(now);
		UUID workspaceId = workspace(tenantId, now);
		List<UserSeed> users = new ArrayList<>(List.of(
				new UserSeed("NEXA_DEV_OWNER_EMAIL", "NEXA_DEV_OWNER_PASSWORD", Set.of("TENANT_ADMIN", "COMPANY_OWNER")),
				new UserSeed("NEXA_DEV_SALES_EMAIL", "NEXA_DEV_SALES_PASSWORD", Set.of("SALES")),
				new UserSeed("NEXA_DEV_WAREHOUSE_EMAIL", "NEXA_DEV_WAREHOUSE_PASSWORD", Set.of("WAREHOUSE")),
				new UserSeed("NEXA_DEV_LOGISTICS_EMAIL", "NEXA_DEV_LOGISTICS_PASSWORD", Set.of("LOGISTICS")),
				new UserSeed("NEXA_DEV_BUYER_EMAIL", "NEXA_DEV_BUYER_PASSWORD", Set.of("BUYER"))));
		addOptionalUser(users, "NEXA_DEV_TENANT_ADMIN_EMAIL", "NEXA_DEV_TENANT_ADMIN_PASSWORD", Set.of("TENANT_ADMIN"));
		addOptionalUser(users, "NEXA_DEV_COMPANY_OWNER_EMAIL", "NEXA_DEV_COMPANY_OWNER_PASSWORD", Set.of("COMPANY_OWNER"));
		UUID buyerUserId = null;
		for (UserSeed user : users) {
			UUID userId = user(user, now);
			if (user.roles().contains("BUYER")) buyerUserId = userId;
			jdbc.update("insert into tenant_management.workspace_membership "
					+ "(id, workspace_id, user_id, membership_type, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0) "
					+ "on conflict (workspace_id, user_id) do update set membership_type = excluded.membership_type, status = 'ACTIVE', updated_at = excluded.updated_at",
					LocalIdentityIds.forMembership(workspaceId, userId), workspaceId, userId, user.roles().contains("BUYER") ? "BUYER" : "INTERNAL", timestamp(now), timestamp(now));
			UUID membershipId = jdbc.queryForObject("select id from tenant_management.workspace_membership where workspace_id=? and user_id=?", UUID.class, workspaceId, userId);
			jdbc.update("delete from tenant_management.membership_role_assignment where membership_id=?", membershipId);
			if (!user.roles().contains("BUYER")) {
				for (String role : user.roles()) jdbc.update("insert into tenant_management.membership_role_assignment (membership_id,tenant_id,workspace_id,role,assigned_at) values (?,?,?,?,?) on conflict do nothing", membershipId, tenantId, workspaceId, role, timestamp(now));
			}
		}
		seedClientAccounts(tenantId, workspaceId, buyerUserId, now);
		seedWarehouse(tenantId, workspaceId, now);
	}

	private void seedWarehouse(UUID tenantId, UUID workspaceId, Instant now) {
		String code = "ICISA-COLD-01";
		UUID warehouseId = LocalIdentityIds.forWarehouse(tenantId, code);
		jdbc.update("insert into warehouse.warehouse (id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) "
				+ "values (?,?,?,?,?,?, 'ACTIVE',?,?) on conflict (tenant_id,workspace_id,code) do nothing",
				warehouseId, tenantId, workspaceId, code, "ICISA Cold Chain Warehouse",
				"Av. Argentina 1234, Callao, Lima, Peru", timestamp(now), timestamp(now));
		UUID persistedWarehouseId = jdbc.queryForObject("select id from warehouse.warehouse where tenant_id=? and workspace_id=? and code=?",
				UUID.class, tenantId, workspaceId, code);
		jdbc.update("insert into warehouse.warehouse_service_configuration "
				+ "(warehouse_id,tenant_id,workspace_id,service_status,priority,preferred,latitude,longitude,updated_at) "
				+ "values (?,?,?,?,?,?,?,?,?) on conflict (warehouse_id) do nothing",
				persistedWarehouseId, tenantId, workspaceId, "OPERATIONAL", 100, true,
				new BigDecimal("-12.0464"), new BigDecimal("-77.0428"), timestamp(now));
	}

	private void seedClientAccounts(UUID tenantId, UUID workspaceId, UUID buyerUserId, Instant now) {
		if (buyerUserId == null) return;
		List<UUID> memberships = jdbc.query("select id from tenant_management.workspace_membership where workspace_id=? and user_id=? and membership_type='BUYER' and status='ACTIVE'", (rs, row) -> rs.getObject(1, UUID.class), workspaceId, buyerUserId);
		if (memberships.isEmpty()) return;
		UUID membershipId = memberships.get(0);
		for (var seed : clientAccountSeedLoader.load()) {
			jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0) on conflict (tenant_id,code) do update set business_name=excluded.business_name,commercial_name=excluded.commercial_name,status=excluded.status,updated_at=excluded.updated_at",
					LocalIdentityIds.forClientAccount(tenantId, seed.code()), tenantId, workspaceId, seed.code(), seed.businessName(), seed.commercialName(), "PE", "RUC", seed.ruc(), seed.segment(), seed.contact(), seed.contactEmail(), seed.phone(), seed.deliveryPreference(), seed.paymentCondition(), "active".equalsIgnoreCase(seed.status()) ? "ACTIVE" : "SUSPENDED", timestamp(now), timestamp(now));
			List<UUID> accounts = jdbc.query("select id from sales.client_account where tenant_id=? and workspace_id=? and code=?", (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, seed.code());
			if (accounts.isEmpty() || !seed.portalAccess()) continue;
			jdbc.update("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?) on conflict (workspace_membership_id) do nothing", accounts.get(0), membershipId, tenantId, workspaceId, timestamp(now));
		}
	}

	private UUID tenant(Instant now) {
		String slug = required("NEXA_DEV_TENANT_SLUG").toLowerCase(java.util.Locale.ROOT);
		String name = required("NEXA_DEV_TENANT_NAME");
		List<UUID> existing = jdbc.query("select id from tenant_management.tenant where slug = ?", (rs, row) -> rs.getObject(1, UUID.class), slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = LocalIdentityIds.forTenant(slug);
		jdbc.update("insert into tenant_management.tenant (id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, 'ACTIVE', ?, ?, 0)", id, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID workspace(UUID tenantId, Instant now) {
		String slug = required("NEXA_DEV_WORKSPACE_SLUG").toLowerCase(java.util.Locale.ROOT);
		String name = required("NEXA_DEV_WORKSPACE_NAME");
		List<UUID> existing = jdbc.query("select id from tenant_management.workspace where tenant_id = ? and slug = ?",
				(rs, row) -> rs.getObject(1, UUID.class), tenantId, slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = LocalIdentityIds.forWorkspace(tenantId, slug);
		jdbc.update("insert into tenant_management.workspace (id, tenant_id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
				id, tenantId, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID user(UserSeed seed, Instant now) {
		String email = required(seed.emailKey()).toLowerCase(java.util.Locale.ROOT);
		String password = password(seed.passwordKey());
		List<UUID> existing = jdbc.query("select id from iam.user_account where normalized_email = ?", (rs, row) -> rs.getObject(1, UUID.class), email);
		UUID id = existing.isEmpty() ? LocalIdentityIds.forUser(email) : existing.get(0);
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

	private String password(String key) {
		String configured = environment.getProperty(key);
		if (configured == null || configured.isBlank()) configured = environment.getProperty("NEXA_DEV_DEMO_PASSWORD");
		if (configured == null || configured.isBlank()) throw new IllegalStateException("Missing local bootstrap variable " + key);
		return configured.trim();
	}

	private void addOptionalUser(List<UserSeed> users, String emailKey, String passwordKey, Set<String> roles) {
		String email = environment.getProperty(emailKey);
		String password = environment.getProperty(passwordKey);
		if ((email == null || email.isBlank()) && (password == null || password.isBlank())) return;
		if (email == null || email.isBlank() || password == null || password.isBlank()) {
			throw new IllegalStateException("Pure role fixture requires both " + emailKey + " and " + passwordKey);
		}
		users.add(new UserSeed(emailKey, passwordKey, roles));
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

	private record UserSeed(String emailKey, String passwordKey, Set<String> roles) {}
}
