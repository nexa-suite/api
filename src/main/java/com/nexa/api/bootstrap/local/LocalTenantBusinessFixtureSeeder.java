package com.nexa.api.bootstrap.local;

import com.nexa.api.bootstrap.local.seed.ClientAccountSeedLoader;
import com.nexa.api.bootstrap.local.seed.ClientAccountSeedRecord;
import com.nexa.api.catalogcommercialpolicy.tenantdatabase.TenantCatalogLocalFixtureProjection;
import com.nexa.api.customerbuyerrelationships.contract.Address;
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
			TenantCatalogLocalFixtureProjection catalogFixtures) {
		this.accountSeeds = accountSeeds;
		this.catalogItemIds = catalogFixtures.catalogItemIds();
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
		List<BuyerAddress> existing = findBuyerAddress(jdbc, addressId);
		if (seed.address() == null || seed.address().isBlank()) {
			if (existing.size() != 1) {
				throw new IllegalStateException("Selected Buyer seed account has no reviewed delivery address");
			}
			BuyerAddress persisted = existing.getFirst();
			validateBuyerAddress(persisted, addressId, tenantId, workspaceId, accountId);
			return;
		}
		Address seedAddress = addressForBuyerSeed(seed);
		if (existing.isEmpty()) {
			Integer otherDefault = jdbc.queryForObject("select count(*) from sales.client_account_address where tenant_id=? "
					+ "and workspace_id=? and client_account_id=? and default_address", Integer.class,
				tenantId, workspaceId, accountId);
			if (otherDefault != null && otherDefault > 0) {
				throw new IllegalStateException("Existing default buyer address conflicts with the local fixture");
			}
			jdbc.update("insert into sales.client_account_address "
					+ "(id,tenant_id,workspace_id,client_account_id,label,recipient_name,recipient_phone,road_type,street_name,street_number,"
					+ "address_line,reference,receiving_instructions,receiving_hours,latitude,longitude,source,department_code,province_code,"
					+ "district_code,default_address,status,version,created_at,updated_at) "
					+ "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true,'ACTIVE',0,?,?) on conflict (id) do nothing",
				addressId, tenantId, workspaceId, accountId, "Buyer delivery · " + seed.code(), seed.contact(), seed.phone(),
				null, null, null, seedAddress.line(), seedAddress.reference(), seed.deliveryPreference(), null,
				seedAddress.latitude(), seedAddress.longitude(), seedAddress.source(), seedAddress.departmentCode(),
				seedAddress.provinceCode(), seedAddress.districtCode(),
				timestamp(now), timestamp(now));
			existing = findBuyerAddress(jdbc, addressId);
		}
		if (existing.size() != 1) throw new IllegalStateException("Deterministic buyer address identity conflicts with existing data");
		BuyerAddress row = existing.getFirst();
		if (!sameScope(row, addressId, tenantId, workspaceId, accountId)) {
			throw new IllegalStateException("Deterministic buyer address identity conflicts with existing data");
		}
		if (!matchesGeography(row, seedAddress)) {
			if (!isRepairableUngeocodedFixture(row, addressId, tenantId, workspaceId, accountId, seed)) {
				throw new IllegalStateException("Existing buyer address differs from the known ungeocoded local fixture");
			}
			int updated = jdbc.update("update sales.client_account_address set department_code=?,province_code=?,district_code=?,"
					+ "latitude=?,longitude=?,source=?,"
					+ "updated_at=?,version=version+1 where id=? and tenant_id=? and workspace_id=? and client_account_id=? "
					+ "and label=? and recipient_name=? and recipient_phone=? and road_type is null and street_name is null "
					+ "and street_number is null and interior is null and address_line=? and postal_code is null and reference=? "
					+ "and receiving_instructions=? and receiving_hours is null and latitude is null and longitude is null "
					+ "and place_id is null and source='MANUAL' and department_code is null and province_code is null "
					+ "and district_code is null and default_address and status='ACTIVE' and version=0",
				seedAddress.departmentCode(), seedAddress.provinceCode(), seedAddress.districtCode(), seedAddress.latitude(),
				seedAddress.longitude(), seedAddress.source(), timestamp(now),
				addressId, tenantId, workspaceId, accountId, "Buyer delivery · " + seed.code(), seed.contact(), seed.phone(),
				seed.address(), seed.deliveryReference(), seed.deliveryPreference());
			if (updated != 1) {
				throw new IllegalStateException("Known ungeocoded buyer fixture changed before its scoped repair");
			}
			existing = findBuyerAddress(jdbc, addressId);
			if (existing.size() != 1) throw new IllegalStateException("Repaired buyer address could not be reloaded");
			row = existing.getFirst();
		}
		if (seedAddress.latitude() != null && row.latitude() == null && row.longitude() == null) {
			if (!isRepairableMissingCoordinatesFixture(row, addressId, tenantId, workspaceId, accountId, seed,
					seedAddress)) {
				throw new IllegalStateException("Existing buyer address differs from the known local fixture without route coordinates");
			}
			int updated = jdbc.update("update sales.client_account_address set latitude=?,longitude=?,source=?,updated_at=?,"
					+ "version=version+1 where id=? and tenant_id=? and workspace_id=? and client_account_id=? "
					+ "and label=? and recipient_name=? and recipient_phone=? and road_type is null and street_name is null "
					+ "and street_number is null and interior is null and address_line=? and postal_code is null and reference=? "
					+ "and receiving_instructions=? and receiving_hours is null and latitude is null and longitude is null "
					+ "and place_id is null and source='MANUAL' and department_code=? and province_code=? and district_code=? "
					+ "and default_address and status='ACTIVE' and version between 0 and 1",
				seedAddress.latitude(), seedAddress.longitude(), seedAddress.source(), timestamp(now), addressId, tenantId,
					workspaceId, accountId, "Buyer delivery · " + seed.code(), seed.contact(), seed.phone(),
					seed.address(), seed.deliveryReference(), seed.deliveryPreference(), seedAddress.departmentCode(),
					seedAddress.provinceCode(), seedAddress.districtCode());
			if (updated != 1) {
				throw new IllegalStateException("Known local buyer fixture changed before its scoped coordinate repair");
			}
			existing = findBuyerAddress(jdbc, addressId);
			if (existing.size() != 1) throw new IllegalStateException("Repaired buyer address could not be reloaded");
			row = existing.getFirst();
		}
		validateBuyerAddress(row, addressId, tenantId, workspaceId, accountId);
	}

	static Address addressForBuyerSeed(ClientAccountSeedRecord seed) {
		if (seed.address() == null || seed.address().isBlank()) {
			throw new IllegalStateException("Selected Buyer seed account has no reviewed delivery address");
		}
		if ("CLI-001".equals(seed.code())) {
			return new Address("STREET", seed.address(), seed.deliveryReference(), "PE", seed.departmentCode(),
					seed.provinceCode(), seed.districtCode(), seed.contact(), seed.phone(), "STREET", null, null, null,
					null, seed.deliveryPreference(), null, new BigDecimal("-12.0725"), new BigDecimal("-77.0685"),
					null, "MAP_PIN");
		}
		return Address.peru("STREET", seed.address(), seed.deliveryReference(), seed.departmentCode(),
				seed.provinceCode(), seed.districtCode());
	}

	private static List<BuyerAddress> findBuyerAddress(JdbcTemplate jdbc, UUID addressId) {
		return jdbc.query("select id,tenant_id,workspace_id,client_account_id,label,recipient_name,recipient_phone,road_type,"
				+ "street_name,street_number,interior,address_line,postal_code,reference,receiving_instructions,receiving_hours,"
				+ "latitude,longitude,place_id,source,department_code,province_code,district_code,default_address,status,version "
				+ "from sales.client_account_address where id=?",
			(rs, row) -> new BuyerAddress(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
					rs.getObject("workspace_id", UUID.class), rs.getObject("client_account_id", UUID.class),
					rs.getString("label"), rs.getString("recipient_name"), rs.getString("recipient_phone"),
					rs.getString("road_type"), rs.getString("street_name"), rs.getString("street_number"),
					rs.getString("interior"), rs.getString("address_line"), rs.getString("postal_code"),
					rs.getString("reference"), rs.getString("receiving_instructions"), rs.getString("receiving_hours"),
					rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude"), rs.getString("place_id"),
					rs.getString("source"), rs.getString("department_code"), rs.getString("province_code"),
					rs.getString("district_code"), rs.getBoolean("default_address"), rs.getString("status"),
					rs.getLong("version")), addressId);
	}

	private static boolean sameScope(BuyerAddress row, UUID addressId, UUID tenantId, UUID workspaceId, UUID accountId) {
		return addressId.equals(row.id()) && tenantId.equals(row.tenantId()) && workspaceId.equals(row.workspaceId())
				&& accountId.equals(row.clientAccountId());
	}

	private static boolean matchesGeography(BuyerAddress row, Address address) {
		return address.departmentCode().equals(row.departmentCode()) && address.provinceCode().equals(row.provinceCode())
				&& address.districtCode().equals(row.districtCode());
	}

	private static boolean isRepairableUngeocodedFixture(BuyerAddress row, UUID addressId, UUID tenantId,
			UUID workspaceId, UUID accountId, ClientAccountSeedRecord seed) {
		return sameScope(row, addressId, tenantId, workspaceId, accountId)
				&& (row.departmentCode() == null && row.provinceCode() == null && row.districtCode() == null)
				&& ("Buyer delivery · " + seed.code()).equals(row.label())
				&& seed.contact().equals(row.recipientName()) && seed.phone().equals(row.recipientPhone())
				&& row.roadType() == null && row.streetName() == null && row.streetNumber() == null
				&& row.interior() == null && seed.address().equals(row.addressLine()) && row.postalCode() == null
				&& java.util.Objects.equals(seed.deliveryReference(), row.reference())
				&& java.util.Objects.equals(seed.deliveryPreference(), row.receivingInstructions())
				&& row.receivingHours() == null && row.latitude() == null && row.longitude() == null
				&& row.placeId() == null && "MANUAL".equals(row.source()) && row.defaultAddress()
				&& "ACTIVE".equalsIgnoreCase(row.status()) && row.version() == 0;
	}

	private static boolean isRepairableMissingCoordinatesFixture(BuyerAddress row, UUID addressId, UUID tenantId,
			UUID workspaceId, UUID accountId, ClientAccountSeedRecord seed, Address address) {
		return sameScope(row, addressId, tenantId, workspaceId, accountId) && matchesGeography(row, address)
				&& ("Buyer delivery · " + seed.code()).equals(row.label())
				&& seed.contact().equals(row.recipientName()) && seed.phone().equals(row.recipientPhone())
				&& row.roadType() == null && row.streetName() == null && row.streetNumber() == null
				&& row.interior() == null && seed.address().equals(row.addressLine()) && row.postalCode() == null
				&& java.util.Objects.equals(seed.deliveryReference(), row.reference())
				&& java.util.Objects.equals(seed.deliveryPreference(), row.receivingInstructions())
				&& row.receivingHours() == null && row.latitude() == null && row.longitude() == null
				&& row.placeId() == null && "MANUAL".equals(row.source()) && row.defaultAddress()
				&& "ACTIVE".equalsIgnoreCase(row.status()) && row.version() >= 0 && row.version() <= 1;
	}

	private static void validateBuyerAddress(BuyerAddress row, UUID addressId, UUID tenantId, UUID workspaceId,
			UUID accountId) {
		if (!sameScope(row, addressId, tenantId, workspaceId, accountId)
				|| row.label() == null || row.label().isBlank() || row.version() < 0) {
			throw new IllegalStateException("Deterministic buyer address identity conflicts with existing data");
		}
		String roadType = row.roadType() == null ? "STREET" : row.roadType();
		new Address(roadType, row.addressLine(), row.reference(), "PE", row.departmentCode(), row.provinceCode(),
				row.districtCode(), row.recipientName(), row.recipientPhone(), roadType, row.streetName(),
				row.streetNumber(), row.interior(), row.postalCode(), row.receivingInstructions(), row.receivingHours(),
				row.latitude(), row.longitude(), row.placeId(), row.source());
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
	private record BuyerAddress(UUID id, UUID tenantId, UUID workspaceId, UUID clientAccountId, String label,
			String recipientName, String recipientPhone, String roadType, String streetName, String streetNumber,
			String interior, String addressLine, String postalCode, String reference, String receivingInstructions,
			String receivingHours, BigDecimal latitude, BigDecimal longitude, String placeId, String source,
			String departmentCode, String provinceCode, String districtCode, boolean defaultAddress, String status,
			long version) { }
	private record SkuSeed(UUID id, String legacyCatalogItemId, String unitOfMeasure) { }
	private record InventoryLotIdentity(UUID id, UUID warehouseId, UUID zoneId, String catalogItemId,
			UUID skuId, String batchNumber) { }
}
