package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseSafetyStockPersistencePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Tenant-scoped current safety-stock policy and its optimistic version. */
@Repository
@Profile("!test")
public class WarehouseSafetyStockPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseSafetyStockPersistencePort {

    @Autowired
    public WarehouseSafetyStockPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort operationalSettings) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings);
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.SafetyStockSummary> safetyStocks(
            CurrentAccessContext context, String warehouseId, String skuId, int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        StringBuilder predicate = new StringBuilder(" where tenant_id=? and workspace_id=?");
        List<Object> args = new ArrayList<>(List.of(tenant(context), workspace(context)));
        if (warehouseId != null && !warehouseId.isBlank()) {
            predicate.append(" and warehouse_id=?");
            args.add(uuid(warehouseId));
        }
        if (skuId != null && !skuId.isBlank()) {
            if (isUuid(skuId)) {
                predicate.append(" and sku_id=?");
                args.add(uuid(skuId));
            } else {
                predicate.append(" and catalog_item_id=?");
                args.add(bounded(skuId, "skuId", 64));
            }
        }
        String from = " from warehouse.safety_stock_policy" + predicate;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);
        List<WarehouseOperationsService.SafetyStockSummary> items = jdbc.query(
                "select id,warehouse_id,sku_id,catalog_item_id,quantity,unit,version,updated_at"
                        + from + " order by updated_at desc,id asc limit ? offset ?",
                (rs, row) -> safetyStock(rs), pageArgs.toArray());
        return new WarehouseOperationsService.Page<>(items, page, size,
                count("select count(*)" + from, args.toArray()));
    }

    @Override
    @Transactional(readOnly = true)
    public WarehouseOperationsService.SafetyStockSummary safetyStock(CurrentAccessContext context, String id) {
        requireRead(context);
        return jdbc.query("select id,warehouse_id,sku_id,catalog_item_id,quantity,unit,version,updated_at"
                        + " from warehouse.safety_stock_policy where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> safetyStock(rs), tenant(context), workspace(context), uuid(id))
                .stream().findFirst().orElseThrow(() -> error("INVENTORY_SAFETY_STOCK_NOT_FOUND", true));
    }

    @Override
    public WarehouseOperationsService.SafetyStockSummary upsertSafetyStock(
            CurrentAccessContext context, WarehouseOperationsService.SafetyStockCommand command,
            long expectedVersion, String idempotencyKey, String correlationId) {
        requireWrite(context);
        requireIdempotency(idempotencyKey);
        if (command == null || expectedVersion < 0) throw error("INVALID_REQUEST", false);

        UUID warehouseId = uuidRequired(command.warehouseId(), "warehouseId");
        requireActiveWarehouse(context, warehouseId);
        SkuReference sku = requestedSku(context, command.skuId(), command.catalogItemId());
        BigDecimal quantity = command.quantity();
        if (quantity == null || quantity.signum() < 0) throw error("INVALID_REQUEST", false);
        String unit = normalizedUnit(command.unit());
        String catalogItemId = sku.legacyCatalogItemId() == null || sku.legacyCatalogItemId().isBlank()
                ? sku.skuCode() : sku.legacyCatalogItemId();
        ensureLotUnits(context, warehouseId, sku.id(), unit);

        String operation = "safety-stock-upsert";
        String hash = requestHash(operation, warehouseId, sku.id(), catalogItemId, quantity, unit, expectedVersion);
        lockIdempotency(context, operation, idempotencyKey);
        IdempotencyRecord prior = idempotent(context, operation, idempotencyKey);
        if (prior != null) {
            requireSamePayload(prior, hash);
            return safetyStock(context, prior.resourceId());
        }
        lockSkuScope(context, sku.id().toString());

        PolicyRow current = jdbc.query("select id,warehouse_id,sku_id,catalog_item_id,quantity,unit,version,updated_at"
                        + " from warehouse.safety_stock_policy where tenant_id=? and workspace_id=?"
                        + " and warehouse_id=? and sku_id=? for update",
                (rs, row) -> new PolicyRow(rs.getObject("id", UUID.class), rs.getString("unit"),
                        rs.getLong("version")), tenant(context), workspace(context), warehouseId, sku.id())
                .stream().findFirst().orElse(null);
        Timestamp now = now();
        UUID policyId;
        if (current == null) {
            if (expectedVersion != 0) throw error("CONCURRENCY_CONFLICT", false);
            policyId = UUID.randomUUID();
            checkUpdated(jdbc.update("insert into warehouse.safety_stock_policy"
                            + "(id,tenant_id,workspace_id,warehouse_id,sku_id,catalog_item_id,quantity,unit,version,created_at,updated_at,actor_membership_id)"
                            + " values (?,?,?,?,?,?,?, ?,0,?,?,?)",
                    policyId, tenant(context), workspace(context), warehouseId, sku.id(), catalogItemId,
                    quantity, unit, now, now, context.membershipId().value()), "safety stock insert");
            appendEvent(context, policyId, "warehouse.safety-stock.configured", "safety-stock", "ACTIVE", now);
        } else {
            if (!current.unit().equals(unit)) throw error("INVENTORY_UNIT_MISMATCH", false);
            if (current.version() != expectedVersion) throw error("CONCURRENCY_CONFLICT", false);
            policyId = current.id();
            checkUpdated(jdbc.update("update warehouse.safety_stock_policy set quantity=?,updated_at=?,version=version+1,actor_membership_id=?"
                            + " where tenant_id=? and workspace_id=? and id=? and version=?",
                    quantity, now, context.membershipId().value(), tenant(context), workspace(context), policyId, expectedVersion),
                    "safety stock update", "CONCURRENCY_CONFLICT");
            appendEvent(context, policyId, "warehouse.safety-stock.updated", "safety-stock", "ACTIVE", now);
        }
        saveIdempotency(context, operation, idempotencyKey, hash, policyId.toString());
        return safetyStock(context, policyId.toString());
    }

    private SkuReference requestedSku(CurrentAccessContext context, String skuId, String catalogItemId) {
        if (skuId != null && !skuId.isBlank()) {
            return isUuid(skuId) ? resolveSku(context, skuId, null) : resolveSku(context, null, bounded(skuId, "skuId", 64));
        }
        return resolveSku(context, null, bounded(catalogItemId, "catalogItemId", 64));
    }

    private void ensureLotUnits(CurrentAccessContext context, UUID warehouseId, UUID skuId, String unit) {
        if (exists("select 1 from warehouse.inventory_lot where tenant_id=? and workspace_id=? and warehouse_id=?"
                        + " and sku_id=? and stock_quantity>0 and upper(unit)<>?",
                tenant(context), workspace(context), warehouseId, skuId, unit)) {
            throw error("INVENTORY_UNIT_MISMATCH", false);
        }
    }

    private void requireRead(CurrentAccessContext context) {
        context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ);
    }

    private void requireWrite(CurrentAccessContext context) {
        context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE);
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value.trim());
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static WarehouseOperationsService.SafetyStockSummary safetyStock(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WarehouseOperationsService.SafetyStockSummary(
                rs.getObject("id").toString(), rs.getObject("warehouse_id").toString(),
                rs.getObject("sku_id").toString(), rs.getString("catalog_item_id"),
                rs.getBigDecimal("quantity"), rs.getString("unit"), rs.getLong("version"),
                instant(rs, "updated_at"));
    }

    private record PolicyRow(UUID id, String unit, long version) { }
}
