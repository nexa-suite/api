package com.nexa.api.bootstrap.local;

import com.nexa.api.bootstrap.local.seed.ClientAccountSeedLoader;
import com.nexa.api.bootstrap.local.seed.ClientAccountSeedRecord;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Opt-in BC02/BC05 local projections, scoped to one already-provisioned Tenant Workspace. */
@Component
@org.springframework.context.annotation.Profile("local-fixtures")
public final class LocalTenantBusinessFixtureSeeder {
	private final ClientAccountSeedLoader accountSeeds;
	private final List<String> catalogItemIds;

	public LocalTenantBusinessFixtureSeeder(ClientAccountSeedLoader accountSeeds,
			CatalogFamilySkuMappingLoader catalogMappings) {
		this.accountSeeds = accountSeeds;
		this.catalogItemIds = catalogMappings.byLegacyCatalogItemId().keySet().stream().sorted().toList();
	}

	public String validateBuyerClientCode(String buyerClientCode) {
		if (buyerClientCode == null || buyerClientCode.isBlank()) {
			throw new IllegalArgumentException("An explicit reviewed Buyer client-account code is required");
		}
		String code = buyerClientCode.strip();
		boolean reviewedPortalAccount = accountSeeds.load().stream()
				.anyMatch(seed -> seed.portalAccess() && seed.code().equals(code));
		if (!reviewedPortalAccount) {
			throw new IllegalArgumentException("Buyer client-account code must identify a reviewed portal-enabled seed account");
		}
		return code;
	}

	public void seed(JdbcTemplate targetJdbc, UUID tenantId, UUID workspaceId, UUID buyerMembershipId,
			String buyerClientCode) {
		if (targetJdbc == null || tenantId == null || workspaceId == null || buyerMembershipId == null) {
			throw new IllegalArgumentException("Exact Tenant, Workspace, and buyer membership are required");
		}
		String selectedClientCode = validateBuyerClientCode(buyerClientCode);
		Instant now = Instant.now();
		seedClientAccounts(targetJdbc, tenantId, workspaceId, buyerMembershipId, selectedClientCode, now);
		seedWarehouse(targetJdbc, tenantId, workspaceId, now);
	}

	private void seedClientAccounts(JdbcTemplate jdbc, UUID tenantId, UUID workspaceId, UUID membershipId,
			String buyerClientCode, Instant now) {
		for (ClientAccountSeedRecord seed : accountSeeds.load()) {
			BigDecimal creditLimit = seed.monthlyCreditLimit() == null ? BigDecimal.ZERO : seed.monthlyCreditLimit();
			BigDecimal creditUsed = seed.monthlyCreditUsed() == null ? BigDecimal.ZERO : seed.monthlyCreditUsed();
			BigDecimal availableCredit = creditLimit.subtract(creditUsed).max(BigDecimal.ZERO);
			jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,"
					+ "tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
					+ "delivery_profile,payment_condition,credit_limit,current_commercial_exposure,available_credit,"
					+ "default_payment_preference,status,created_at,updated_at,version) "
					+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0) on conflict (tenant_id,code) do nothing",
				LocalIdentityIds.forClientAccount(tenantId, seed.code()), tenantId, workspaceId, seed.code(),
				seed.businessName(), seed.commercialName(), "PE", "RUC", seed.ruc(), seed.segment(), seed.contact(),
				seed.contactEmail(), seed.phone(), seed.deliveryPreference(), seed.paymentCondition(), creditLimit,
				creditUsed, availableCredit, seed.paymentCondition(), activeStatus(seed.status()), timestamp(now), timestamp(now));

			List<ClientAccount> accounts = jdbc.query("select id,workspace_id,business_name from sales.client_account "
					+ "where tenant_id=? and code=?", (rs, row) -> new ClientAccount(rs.getObject("id", UUID.class),
						rs.getObject("workspace_id", UUID.class), rs.getString("business_name")), tenantId, seed.code());
			if (accounts.size() != 1 || !workspaceId.equals(accounts.getFirst().workspaceId())
					|| !seed.businessName().equals(accounts.getFirst().businessName())) {
				throw new IllegalStateException("Existing client account conflicts with the local fixture");
			}
			if (!seed.portalAccess() || !buyerClientCode.equals(seed.code())) continue;
			UUID accountId = accounts.getFirst().id();
			jdbc.update("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "
					+ "values (?,?,?,?,?) on conflict (workspace_membership_id) do nothing",
				accountId, membershipId, tenantId, workspaceId, timestamp(now));
			List<UUID> linkedAccounts = jdbc.query("select client_account_id from sales.client_account_membership "
					+ "where workspace_membership_id=? and tenant_id=? and workspace_id=?",
				(rs, row) -> rs.getObject(1, UUID.class), membershipId, tenantId, workspaceId);
			if (!linkedAccounts.contains(accountId)) {
				throw new IllegalStateException("Buyer membership is already linked to another client account");
			}
			seedBuyerAddress(jdbc, tenantId, workspaceId, accountId, seed, now);
		}
	}

	private void seedBuyerAddress(JdbcTemplate jdbc, UUID tenantId, UUID workspaceId, UUID accountId,
			ClientAccountSeedRecord seed, Instant now) {
		String addressKey = "CLI-001".equals(seed.code()) ? "buyer-demo-pueblo-libre"
				: "buyer-demo-" + seed.code().toLowerCase(java.util.Locale.ROOT);
		UUID addressId = LocalIdentityIds.forClientAccountAddress(accountId, addressKey);
		Integer existing = jdbc.queryForObject("select count(*) from sales.client_account_address where id=? "
				+ "and tenant_id=? and workspace_id=? and client_account_id=?", Integer.class,
			addressId, tenantId, workspaceId, accountId);
		if (existing != null && existing > 0) return;
		Integer otherDefault = jdbc.queryForObject("select count(*) from sales.client_account_address where tenant_id=? "
				+ "and workspace_id=? and client_account_id=? and default_address", Integer.class,
			tenantId, workspaceId, accountId);
		if (otherDefault != null && otherDefault > 0) {
			throw new IllegalStateException("Existing default buyer address conflicts with the local fixture");
		}
		if (seed.address() == null || seed.address().isBlank()) {
			throw new IllegalStateException("Selected Buyer seed account has no reviewed delivery address");
		}
		jdbc.update("insert into sales.client_account_address "
				+ "(id,tenant_id,workspace_id,client_account_id,label,recipient_name,recipient_phone,road_type,street_name,street_number,"
				+ "address_line,reference,receiving_instructions,receiving_hours,latitude,longitude,source,department_code,province_code,"
				+ "district_code,default_address,status,version,created_at,updated_at) "
				+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,'ACTIVE',0,?,?) on conflict (id) do nothing",
			addressId, tenantId, workspaceId, accountId, "Buyer delivery · " + seed.code(), seed.contact(), seed.phone(),
			null, null, null, seed.address(), seed.deliveryReference(), seed.deliveryPreference(), null, null,
			null, "MANUAL", null, null, null, timestamp(now), timestamp(now));
		Integer persisted = jdbc.queryForObject("select count(*) from sales.client_account_address where id=? "
				+ "and tenant_id=? and workspace_id=? and client_account_id=?", Integer.class,
			addressId, tenantId, workspaceId, accountId);
		if (persisted == null || persisted != 1) {
			throw new IllegalStateException("Deterministic buyer address identity conflicts with existing data");
		}
	}

	private void seedWarehouse(JdbcTemplate jdbc, UUID tenantId, UUID workspaceId, Instant now) {
		String code = "ICISA-COLD-01";
		UUID warehouseId = LocalIdentityIds.forWarehouse(tenantId, code);
		jdbc.update("insert into warehouse.warehouse (id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) "
				+ "values (?,?,?,?,?,?, 'ACTIVE',?,?) on conflict (tenant_id,workspace_id,code) do nothing",
			warehouseId, tenantId, workspaceId, code, "Temporary cold-chain warehouse",
			"Av. Arnaldo Márquez 1772, Jesús María, Lima, Lima, Perú", timestamp(now), timestamp(now));
		List<UUID> warehouses = jdbc.query("select id from warehouse.warehouse where tenant_id=? and workspace_id=? and code=?",
				(rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, code);
		if (warehouses.size() != 1 || !warehouseId.equals(warehouses.getFirst())) {
			throw new IllegalStateException("Existing local warehouse conflicts with its deterministic identity");
		}
		UUID persistedWarehouseId = warehouses.getFirst();
		jdbc.update("insert into warehouse.warehouse_service_configuration "
				+ "(warehouse_id,tenant_id,workspace_id,service_status,priority,preferred,latitude,longitude,updated_at) "
				+ "values (?,?,?,?,?,?,?,?,?) on conflict (warehouse_id) do nothing",
			persistedWarehouseId, tenantId, workspaceId, "OPERATIONAL", 100, true,
			new BigDecimal("-12.0785"), new BigDecimal("-77.0525"), timestamp(now));

		UUID zoneId = LocalIdentityIds.forWarehouseZone(persistedWarehouseId, "CHILLED-A");
		jdbc.update("insert into warehouse.storage_zone "
				+ "(id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,created_at,updated_at,version) "
				+ "values (?,?,?,?,?,?, 'CHILLED',?,?, 'ACTIVE',?,?,0) on conflict (tenant_id,workspace_id,warehouse_id,code) do nothing",
			zoneId, tenantId, workspaceId, persistedWarehouseId, "CHILLED-A", "Cámara refrigerada A",
			new BigDecimal("0"), new BigDecimal("8"), timestamp(now), timestamp(now));
		List<UUID> zones = jdbc.query("select id from warehouse.storage_zone where tenant_id=? and workspace_id=? "
				+ "and warehouse_id=? and code=?", (rs, row) -> rs.getObject(1, UUID.class),
			tenantId, workspaceId, persistedWarehouseId, "CHILLED-A");
		if (zones.size() != 1 || !zoneId.equals(zones.getFirst())) {
			throw new IllegalStateException("Existing local warehouse zone conflicts with its deterministic identity");
		}
		seedInventory(jdbc, tenantId, workspaceId, persistedWarehouseId, zones.getFirst(), now);
	}

	private void seedInventory(JdbcTemplate jdbc, UUID tenantId, UUID workspaceId, UUID warehouseId,
			UUID zoneId, Instant now) {
		String placeholders = String.join(",", java.util.Collections.nCopies(catalogItemIds.size(), "?"));
		List<Object> args = new ArrayList<>();
		args.add(tenantId);
		args.add(workspaceId);
		args.addAll(catalogItemIds);
		List<SkuSeed> skus = jdbc.query("select id,legacy_catalog_item_id,unit_of_measure from catalog_management.sellable_sku "
				+ "where tenant_id=? and workspace_id=? and status='ACTIVE' and visible "
				+ "and legacy_catalog_item_id in (" + placeholders + ") order by sku_code",
			(rs, row) -> new SkuSeed(rs.getObject("id", UUID.class), rs.getString("legacy_catalog_item_id"),
				rs.getString("unit_of_measure")), args.toArray());
		for (SkuSeed sku : skus) {
			String batch = "LOCAL-FOUNDATION-" + sku.legacyCatalogItemId();
			UUID lotId = LocalIdentityIds.forInventoryLot(warehouseId, sku.id(), batch);
			jdbc.update("insert into warehouse.inventory_lot "
					+ "(id,tenant_id,workspace_id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number,expiration_date,received_at,"
					+ "stock_quantity,reserved_quantity,unit,status,temperature_range_snapshot,version) "
					+ "values (?,?,?,?,?,?,?,?,current_date + 365,current_timestamp - interval '1 day',1000,0,?,'AVAILABLE','0-8 C',0) "
					+ "on conflict do nothing", lotId, tenantId, workspaceId, warehouseId, zoneId,
				sku.legacyCatalogItemId(), sku.id(), batch,
				sku.unitOfMeasure() == null || sku.unitOfMeasure().isBlank() ? "UNIT" : sku.unitOfMeasure());
			List<InventoryLotIdentity> lots = jdbc.query("select id,warehouse_id,zone_id,catalog_item_id,sku_id,batch_number "
					+ "from warehouse.inventory_lot where tenant_id=? and workspace_id=? and "
					+ "(id=? or (warehouse_id=? and sku_id=? and batch_number=?))",
				(rs, row) -> new InventoryLotIdentity(rs.getObject("id", UUID.class),
					rs.getObject("warehouse_id", UUID.class), rs.getObject("zone_id", UUID.class),
					rs.getString("catalog_item_id"), rs.getObject("sku_id", UUID.class), rs.getString("batch_number")),
				tenantId, workspaceId, lotId, warehouseId, sku.id(), batch);
			if (lots.size() != 1) {
				throw new IllegalStateException("Existing local inventory lot conflicts with its deterministic identity");
			}
			InventoryLotIdentity lot = lots.getFirst();
			if (!lotId.equals(lot.id()) || !warehouseId.equals(lot.warehouseId()) || !zoneId.equals(lot.zoneId())
					|| !sku.legacyCatalogItemId().equals(lot.catalogItemId()) || !sku.id().equals(lot.skuId())
					|| !batch.equals(lot.batchNumber())) {
				throw new IllegalStateException("Existing local inventory lot does not match its reviewed SKU and warehouse references");
			}
		}
	}

	private static String activeStatus(String status) {
		return "active".equalsIgnoreCase(status) ? "ACTIVE" : "SUSPENDED";
	}

	private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }

	private record ClientAccount(UUID id, UUID workspaceId, String businessName) { }
	private record SkuSeed(UUID id, String legacyCatalogItemId, String unitOfMeasure) { }
	private record InventoryLotIdentity(UUID id, UUID warehouseId, UUID zoneId, String catalogItemId,
			UUID skuId, String batchNumber) { }
}
