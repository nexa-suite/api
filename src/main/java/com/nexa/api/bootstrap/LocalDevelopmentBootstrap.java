package com.nexa.api.bootstrap;

import com.nexa.api.sales.infrastructure.seed.ClientAccountSeedLoader;
import com.nexa.api.sales.infrastructure.seed.ClientAccountSeedRecord;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.nexa.api.shared.infrastructure.security.RlsRequestScope;

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
	private final TransactionTemplate transactionTemplate;

	public LocalDevelopmentBootstrap(JdbcTemplate jdbc, org.springframework.core.env.Environment environment, Clock clock, ClientAccountSeedLoader clientAccountSeedLoader,
			PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.environment = environment;
		this.encoder = new BCryptPasswordEncoder(environment.getProperty("nexa.security.bcrypt-strength", Integer.class, 12));
		this.clock = clock;
		this.clientAccountSeedLoader = clientAccountSeedLoader;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.LOWEST_PRECEDENCE - 30)
	public void seed() {
		Instant now = clock.instant();
		UUID tenantId = tenant(now);
		UUID workspaceId = workspace(tenantId, now);
		RlsRequestScope.set(tenantId, workspaceId);
		List<UserSeed> users = new ArrayList<>(List.of(
				new UserSeed("NEXA_DEV_OWNER_EMAIL", "NEXA_DEV_OWNER_PASSWORD", Set.of("TENANT_ADMIN", "COMPANY_OWNER")),
				new UserSeed("NEXA_DEV_SALES_EMAIL", "NEXA_DEV_SALES_PASSWORD", Set.of("SALES")),
				new UserSeed("NEXA_DEV_WAREHOUSE_EMAIL", "NEXA_DEV_WAREHOUSE_PASSWORD", Set.of("WAREHOUSE")),
				new UserSeed("NEXA_DEV_LOGISTICS_EMAIL", "NEXA_DEV_LOGISTICS_PASSWORD", Set.of("LOGISTICS")),
				new UserSeed("NEXA_DEV_BUYER_EMAIL", "NEXA_DEV_BUYER_PASSWORD", Set.of("BUYER"))));
		addOptionalUser(users, "NEXA_DEV_TENANT_ADMIN_EMAIL", "NEXA_DEV_TENANT_ADMIN_PASSWORD", Set.of("TENANT_ADMIN"));
		UUID buyerUserId = null;
		for (UserSeed user : users) {
			UUID userId = user(user, now);
			if (user.roles().contains("BUYER")) buyerUserId = userId;
			jdbc.update("insert into tenant_management.workspace_membership "
					+ "(id, workspace_id, user_id, membership_type, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0) "
					+ "on conflict (workspace_id, user_id) do update set membership_type = excluded.membership_type, status = 'ACTIVE', updated_at = excluded.updated_at",
					LocalIdentityIds.forMembership(workspaceId, userId), workspaceId, userId, user.roles().contains("BUYER") ? "BUYER" : "INTERNAL", timestamp(now), timestamp(now));
			UUID membershipId = jdbc.queryForObject("select id from tenant_management.workspace_membership where workspace_id=? and user_id=?", UUID.class, workspaceId, userId);
			jdbc.update("delete from tenant_management.membership_role_definition a using tenant_management.role_definition r where a.role_id=r.id and a.membership_id=? and r.tenant_id is null", membershipId);
			for (String role : user.roles()) jdbc.update("insert into tenant_management.membership_role_definition (membership_id,tenant_id,workspace_id,role_id,assigned_at) select ?,?,?,r.id,? from tenant_management.role_definition r where r.tenant_id is null and r.code=lower(?) on conflict do nothing", membershipId, tenantId, workspaceId, timestamp(now), role);
			jdbc.update("insert into tenant_management.membership_authorization_state (membership_id,tenant_id,workspace_id,authorization_version,updated_at) values (?,?,?,?,?) on conflict (membership_id) do update set authorization_version=tenant_management.membership_authorization_state.authorization_version+1,updated_at=excluded.updated_at", membershipId, tenantId, workspaceId, 0, timestamp(now));
		}
		seedClientAccounts(tenantId, workspaceId, buyerUserId, now);
		RlsRequestScope.clear();
	}

	/**
	 * Warehouse lots depend on the canonical SKU projection. Run this after the
	 * deterministic catalog import and Product Family/SKU reconciliation.
	 */
	@EventListener(ApplicationReadyEvent.class)
	@Order(Ordered.LOWEST_PRECEDENCE)
	public void seedWarehouseAfterCatalogReconciliation() {
		Instant now = clock.instant();
		UUID tenantId = tenant(now);
		UUID workspaceId = workspace(tenantId, now);
		RlsRequestScope.set(tenantId, workspaceId);
		try {
			transactionTemplate.executeWithoutResult(status -> seedWarehouse(tenantId, workspaceId, now));
		} finally {
			RlsRequestScope.clear();
		}
	}

	private void seedWarehouse(UUID tenantId, UUID workspaceId, Instant now) {
		String code = "ICISA-COLD-01";
		UUID warehouseId = LocalIdentityIds.forWarehouse(tenantId, code);
		jdbc.update("insert into warehouse.warehouse (id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) "
				+ "values (?,?,?,?,?,?, 'ACTIVE',?,?) on conflict (tenant_id,workspace_id,code) do update set name=excluded.name,address=excluded.address,updated_at=excluded.updated_at",
				warehouseId, tenantId, workspaceId, code, "Temporary cold-chain warehouse",
				"Av. Arnaldo Márquez 1772, Jesús María, Lima, Lima, Perú", timestamp(now), timestamp(now));
		UUID persistedWarehouseId = jdbc.queryForObject("select id from warehouse.warehouse where tenant_id=? and workspace_id=? and code=?",
				UUID.class, tenantId, workspaceId, code);
		jdbc.update("insert into warehouse.warehouse_service_configuration "
				+ "(warehouse_id,tenant_id,workspace_id,service_status,priority,preferred,latitude,longitude,updated_at) "
				+ "values (?,?,?,?,?,?,?,?,?) on conflict (warehouse_id) do update set latitude=excluded.latitude,longitude=excluded.longitude,updated_at=excluded.updated_at",
				persistedWarehouseId, tenantId, workspaceId, "OPERATIONAL", 100, true,
				new BigDecimal("-12.0785"), new BigDecimal("-77.0525"), timestamp(now));
		UUID zoneId = LocalIdentityIds.forWarehouseZone(persistedWarehouseId, "CHILLED-A");
		jdbc.update("insert into warehouse.storage_zone (id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,created_at,updated_at,version) "
				+ "values (?,?,?,?,?,?, 'CHILLED',?,?, 'ACTIVE',?,?,0) on conflict (tenant_id,workspace_id,warehouse_id,code) do nothing",
				zoneId, tenantId, workspaceId, persistedWarehouseId, "CHILLED-A", "Cámara refrigerada A",
				new BigDecimal("0"), new BigDecimal("8"), timestamp(now), timestamp(now));
		UUID persistedZoneId = jdbc.queryForObject("select id from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and code=?",
				UUID.class, tenantId, workspaceId, persistedWarehouseId, "CHILLED-A");
		seedInventory(tenantId, workspaceId, persistedWarehouseId, persistedZoneId, now);
	}

	private void seedInventory(UUID tenantId, UUID workspaceId, UUID warehouseId, UUID zoneId, Instant now) {
		List<SkuSeed> skus = jdbc.query("select id,legacy_catalog_item_id,unit_of_measure from catalog_management.sellable_sku where tenant_id=? and workspace_id=? and status='ACTIVE' and visible and legacy_catalog_item_id is not null and btrim(legacy_catalog_item_id) <> '' order by sku_code",
				(rs, row) -> new SkuSeed(rs.getObject("id", UUID.class), rs.getString("legacy_catalog_item_id"), rs.getString("unit_of_measure")), tenantId, workspaceId);
		for (SkuSeed sku : skus) {
			String batch = "LOCAL-FOUNDATION-" + sku.legacyCatalogItemId();
			UUID lotId = LocalIdentityIds.forInventoryLot(warehouseId, sku.id(), batch);
			jdbc.update("insert into warehouse.inventory_lot (id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,version) "
					+ "values (?,?,?,?,?,?,?,?,current_date + 365,current_timestamp - interval '1 day',1000,0,?,'AVAILABLE','0-8 C',0) on conflict do nothing",
					lotId, tenantId, workspaceId, warehouseId, zoneId, sku.legacyCatalogItemId(), sku.id(), batch,
					sku.unitOfMeasure() == null || sku.unitOfMeasure().isBlank() ? "UNIT" : sku.unitOfMeasure());
		}
	}

	private void seedClientAccounts(UUID tenantId, UUID workspaceId, UUID buyerUserId, Instant now) {
		if (buyerUserId == null) return;
		List<UUID> memberships = jdbc.query("select id from tenant_management.workspace_membership where workspace_id=? and user_id=? and membership_type='BUYER' and status='ACTIVE'", (rs, row) -> rs.getObject(1, UUID.class), workspaceId, buyerUserId);
		if (memberships.isEmpty()) return;
		UUID membershipId = memberships.get(0);
		for (var seed : clientAccountSeedLoader.load()) {
			BigDecimal creditLimit = seed.monthlyCreditLimit() == null ? BigDecimal.ZERO : seed.monthlyCreditLimit();
			BigDecimal creditUsed = seed.monthlyCreditUsed() == null ? BigDecimal.ZERO : seed.monthlyCreditUsed();
			BigDecimal availableCredit = creditLimit.subtract(creditUsed).max(BigDecimal.ZERO);
			jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,credit_limit,current_commercial_exposure,available_credit,default_payment_preference,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0) on conflict (tenant_id,code) do update set business_name=excluded.business_name,commercial_name=excluded.commercial_name,payment_condition=excluded.payment_condition,credit_limit=excluded.credit_limit,current_commercial_exposure=excluded.current_commercial_exposure,available_credit=excluded.available_credit,default_payment_preference=excluded.default_payment_preference,status=excluded.status,updated_at=excluded.updated_at",
					LocalIdentityIds.forClientAccount(tenantId, seed.code()), tenantId, workspaceId, seed.code(), seed.businessName(), seed.commercialName(), "PE", "RUC", seed.ruc(), seed.segment(), seed.contact(), seed.contactEmail(), seed.phone(), seed.deliveryPreference(), seed.paymentCondition(), creditLimit, creditUsed, availableCredit, seed.paymentCondition(), "active".equalsIgnoreCase(seed.status()) ? "ACTIVE" : "SUSPENDED", timestamp(now), timestamp(now));
				List<UUID> accounts = jdbc.query("select id from sales.client_account where tenant_id=? and workspace_id=? and code=?", (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, seed.code());
				if (accounts.isEmpty() || !seed.portalAccess()) continue;
				jdbc.update("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?) on conflict (workspace_membership_id) do nothing", accounts.get(0), membershipId, tenantId, workspaceId, timestamp(now));
				if ("CLI-001".equals(seed.code())) seedBuyerAddress(tenantId, workspaceId, accounts.get(0), seed, now);
			}
		}

	private void seedBuyerAddress(UUID tenantId, UUID workspaceId, UUID clientAccountId,
			ClientAccountSeedRecord seed, Instant now) {
		UUID addressId = LocalIdentityIds.forClientAccountAddress(clientAccountId, "buyer-demo-pueblo-libre");
		jdbc.update("update sales.client_account_address set default_address=false,updated_at=? where tenant_id=? and workspace_id=? and client_account_id=? and id<>? and default_address",
				timestamp(now), tenantId, workspaceId, clientAccountId, addressId);
		jdbc.update("insert into sales.client_account_address "
				+ "(id,tenant_id,workspace_id,client_account_id,label,recipient_name,recipient_phone,road_type,street_name,street_number,"
				+ "address_line,reference,receiving_instructions,receiving_hours,latitude,longitude,source,department_code,province_code,"
				+ "district_code,default_address,status,version,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,'ACTIVE',0,?,?) "
				+ "on conflict (id) do update set label=excluded.label,recipient_name=excluded.recipient_name,recipient_phone=excluded.recipient_phone,"
				+ "road_type=excluded.road_type,street_name=excluded.street_name,street_number=excluded.street_number,address_line=excluded.address_line,"
				+ "reference=excluded.reference,receiving_instructions=excluded.receiving_instructions,receiving_hours=excluded.receiving_hours,"
				+ "latitude=excluded.latitude,longitude=excluded.longitude,source=excluded.source,department_code=excluded.department_code,"
				+ "province_code=excluded.province_code,district_code=excluded.district_code,default_address=true,status='ACTIVE',updated_at=excluded.updated_at",
				addressId, tenantId, workspaceId, clientAccountId, "Buyer delivery · Pueblo Libre", seed.contact(), seed.phone(),
				"AVENUE", "Av. Sucre", "1992", "Av. Sucre 1992", "Ingreso de proveedores por recepción principal",
				"Validar ventana de cadena de frío con el comprador", "08:00-17:00", new BigDecimal("-12.0725"),
				new BigDecimal("-77.0685"), "MAP_PIN", "15", "1501", "150121", timestamp(now), timestamp(now));
	}

	private UUID tenant(Instant now) {
		String slug = defaulted("NEXA_DEV_TENANT_SLUG", "icisa").toLowerCase(java.util.Locale.ROOT);
		String name = defaulted("NEXA_DEV_TENANT_NAME", "ICISA");
		List<UUID> existing = jdbc.query("select id from tenant_management.tenant where slug = ?", (rs, row) -> rs.getObject(1, UUID.class), slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = LocalIdentityIds.forTenant(slug);
		jdbc.update("insert into tenant_management.tenant (id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, 'ACTIVE', ?, ?, 0)", id, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID workspace(UUID tenantId, Instant now) {
		String slug = defaulted("NEXA_DEV_WORKSPACE_SLUG", "icisa").toLowerCase(java.util.Locale.ROOT);
		String name = defaulted("NEXA_DEV_WORKSPACE_NAME", "ICISA Workspace");
		List<UUID> existing = jdbc.query("select id from tenant_management.workspace where tenant_id = ? and slug = ?",
				(rs, row) -> rs.getObject(1, UUID.class), tenantId, slug);
		if (!existing.isEmpty()) return existing.get(0);
		UUID id = LocalIdentityIds.forWorkspace(tenantId, slug);
		jdbc.update("insert into tenant_management.workspace (id, tenant_id, name, slug, status, created_at, updated_at, version) values (?, ?, ?, ?, 'ACTIVE', ?, ?, 0)",
				id, tenantId, name, slug, timestamp(now), timestamp(now));
		return id;
	}

	private UUID user(UserSeed seed, Instant now) {
		String email = defaulted(seed.emailKey(), defaultEmail(seed.emailKey())).toLowerCase(java.util.Locale.ROOT);
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

	private String defaulted(String key, String fallback) {
		String value = environment.getProperty(key);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private String password(String key) {
		String configured = environment.getProperty(key);
		if (configured == null || configured.isBlank()) configured = environment.getProperty("NEXA_DEV_DEMO_PASSWORD");
		return configured == null || configured.isBlank() ? "NexaLocal!2026#" : configured.trim();
	}

	private static String defaultEmail(String key) {
		return switch (key) {
			case "NEXA_DEV_TENANT_ADMIN_EMAIL" -> "tenant.admin@icisa.test";
			case "NEXA_DEV_OWNER_EMAIL" -> "owner@icisa.test";
			case "NEXA_DEV_SALES_EMAIL" -> "sales@icisa.test";
			case "NEXA_DEV_WAREHOUSE_EMAIL" -> "warehouse@icisa.test";
			case "NEXA_DEV_LOGISTICS_EMAIL" -> "logistics@icisa.test";
			case "NEXA_DEV_BUYER_EMAIL" -> "buyer@icisa.test";
			default -> throw new IllegalArgumentException("No deterministic local email for " + key);
		};
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
	private record SkuSeed(UUID id, String legacyCatalogItemId, String unitOfMeasure) {}
}
