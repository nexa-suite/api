package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Inventory-owned atomic availability backing for commercial commitments. */
@Repository
@Profile("!test")
public class WarehouseCommercialBackingAdapter implements InventoryBackingCommands {
    private final JdbcTemplate jdbc;

    public WarehouseCommercialBackingAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public BackingResult establish(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId,
                                   List<RequestedLine> lines, Instant now) {
        if (lines == null || lines.isEmpty()) throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        List<RequestedLine> requested = lines.stream().sorted(Comparator.comparing(line -> line.skuId().toString())).toList();
        if (requested.stream().map(RequestedLine::skuId).distinct().count() != requested.size()) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }

        BackingRow existing = jdbc.query(
                "select id,status from warehouse.inventory_backing where tenant_id=? and workspace_id=? and commercial_commitment_id=? for update",
                (rs, row) -> new BackingRow(rs.getObject(1, UUID.class), rs.getString(2)),
                tenantId, workspaceId, commercialCommitmentId).stream().findFirst().orElse(null);
        if (existing != null && "BACKED".equals(existing.status())) return load(tenantId, workspaceId, existing.id());

        List<LotRow> lots = lockEligibleLots(tenantId, workspaceId, requested);
        Map<UUID, List<LotRow>> lotsBySku = lots.stream().collect(Collectors.groupingBy(LotRow::skuId, LinkedHashMap::new, Collectors.toList()));
        Map<StockKey, BigDecimal> activeBackingByStock = activeBackingByStock(tenantId, workspaceId, requested);
        List<PositionPlan> plan = new ArrayList<>();
        for (RequestedLine line : requested) {
            List<LotRow> skuLots = lotsBySku.getOrDefault(line.skuId(), List.of());
            Map<UUID, BigDecimal> physicalByWarehouse = new LinkedHashMap<>();
            for (LotRow lot : skuLots) physicalByWarehouse.merge(lot.warehouseId(), lot.available(), BigDecimal::add);
            BigDecimal required = line.quantity();
            for (Map.Entry<UUID, BigDecimal> warehouse : physicalByWarehouse.entrySet()) {
                BigDecimal safety = safetyStock(tenantId, workspaceId, warehouse.getKey(), line.skuId());
                BigDecimal alreadyBacked = activeBackingByStock.getOrDefault(new StockKey(line.skuId(), warehouse.getKey()), BigDecimal.ZERO);
                BigDecimal capacity = warehouse.getValue().subtract(safety).subtract(alreadyBacked).max(BigDecimal.ZERO);
                if (capacity.signum() <= 0) continue;
                BigDecimal selected = capacity.min(required);
                plan.add(new PositionPlan(line, warehouse.getKey(), selected));
                required = required.subtract(selected);
                if (required.signum() == 0) break;
            }
            if (required.signum() > 0) throw new WarehouseOperationsService.WarehouseException("INSUFFICIENT_SELLABLE_AVAILABILITY", false);
        }

        UUID backingId = existing == null ? UUID.randomUUID() : existing.id();
        if (existing == null) {
            jdbc.update("insert into warehouse.inventory_backing(id,tenant_id,workspace_id,commercial_commitment_id,status,requested_at,updated_at,version) values (?,?,?,?, 'REQUESTED',?,?,0)",
                    backingId, tenantId, workspaceId, commercialCommitmentId, timestamp(now), timestamp(now));
        } else {
            jdbc.update("update warehouse.inventory_backing set status='REQUESTED',completed_at=null,released_at=null,release_reason=null,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=?",
                    timestamp(now), tenantId, workspaceId, backingId);
            jdbc.update("delete from warehouse.inventory_backing_line where tenant_id=? and workspace_id=? and backing_id=?",
                    tenantId, workspaceId, backingId);
        }

        Map<UUID, UUID> lineIds = new HashMap<>();
        for (RequestedLine line : requested) {
            UUID lineId = UUID.randomUUID();
            lineIds.put(line.skuId(), lineId);
            jdbc.update("insert into warehouse.inventory_backing_line(id,tenant_id,workspace_id,backing_id,sku_id,catalog_item_id,requested_quantity,backed_quantity,unit,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                    lineId, tenantId, workspaceId, backingId, line.skuId(), line.catalogItemId(), line.quantity(), BigDecimal.ZERO, line.unit(), timestamp(now));
        }
        Map<UUID, BigDecimal> backedBySku = new HashMap<>();
        for (PositionPlan position : plan) {
            jdbc.update("insert into warehouse.inventory_backing_position(id,tenant_id,workspace_id,backing_line_id,warehouse_id,quantity,created_at) values (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), tenantId, workspaceId, lineIds.get(position.line().skuId()), position.warehouseId(), position.quantity(), timestamp(now));
            backedBySku.merge(position.line().skuId(), position.quantity(), BigDecimal::add);
        }
        for (RequestedLine line : requested) {
            jdbc.update("update warehouse.inventory_backing_line set backed_quantity=? where tenant_id=? and workspace_id=? and id=?",
                    backedBySku.getOrDefault(line.skuId(), BigDecimal.ZERO), tenantId, workspaceId, lineIds.get(line.skuId()));
        }
        jdbc.update("update warehouse.inventory_backing set status='BACKED',completed_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=?",
                timestamp(now), timestamp(now), tenantId, workspaceId, backingId);
        return load(tenantId, workspaceId, backingId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID tenantId, UUID workspaceId, UUID commercialCommitmentId, String reason, Instant now) {
        jdbc.update("update warehouse.inventory_backing set status='RELEASED',released_at=?,release_reason=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and commercial_commitment_id=? and status='BACKED'",
                timestamp(now), normalize(reason), timestamp(now), tenantId, workspaceId, commercialCommitmentId);
    }

    private List<LotRow> lockEligibleLots(UUID tenantId, UUID workspaceId, List<RequestedLine> requested) {
        String predicate = requested.stream().map(line -> "(l.sku_id=? and l.catalog_item_id=? and l.unit=?)").collect(Collectors.joining(" or "));
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        for (RequestedLine line : requested) { args.add(line.skuId()); args.add(line.catalogItemId()); args.add(line.unit()); }
        return jdbc.query("select l.id,l.sku_id,l.warehouse_id,(l.stock_quantity-l.reserved_quantity) available "
                        + "from warehouse.inventory_lot l "
                        + "join catalog_management.sellable_sku sku on sku.tenant_id=l.tenant_id and sku.workspace_id=l.workspace_id and sku.id=l.sku_id "
                        + "join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=? and sku.status='ACTIVE' and l.status='AVAILABLE' and l.expiration_date>current_date "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' "
                        + "and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "and (sku.temperature_min is null or (z.temperature_min is not null and z.temperature_min<=sku.temperature_min)) "
                        + "and (sku.temperature_max is null or (z.temperature_max is not null and z.temperature_max>=sku.temperature_max)) "
                        + "and ((sku.temperature_min is null and sku.temperature_max is null) or (l.temperature_value is not null and (sku.temperature_min is null or l.temperature_value>=sku.temperature_min) and (sku.temperature_max is null or l.temperature_value<=sku.temperature_max))) "
                        + "and not exists (select 1 from warehouse.inventory_temperature_evaluation evaluation where evaluation.tenant_id=l.tenant_id and evaluation.workspace_id=l.workspace_id and evaluation.lot_id=l.id and evaluation.status='OPEN' and evaluation.disposition='HOLD') "
                        + "and coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition where disposition.tenant_id=l.tenant_id and disposition.workspace_id=l.workspace_id and disposition.lot_id=l.id order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') not in ('HOLD','WASTE','RETURN_TO_SUPPLIER') "
                        + "and l.stock_quantity>l.reserved_quantity and (" + predicate + ") "
                        + "order by l.sku_id,l.warehouse_id,l.expiration_date,l.id for update of l",
                (rs, row) -> new LotRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getBigDecimal(4)), args.toArray());
    }

    private Map<StockKey, BigDecimal> activeBackingByStock(UUID tenantId, UUID workspaceId, List<RequestedLine> requested) {
        String placeholders = requested.stream().map(ignored -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(tenantId, workspaceId));
        requested.forEach(line -> args.add(line.skuId()));
        return jdbc.query("select l.sku_id,p.warehouse_id,coalesce(sum(p.quantity),0) from warehouse.inventory_backing_position p "
                        + "join warehouse.inventory_backing_line l on l.tenant_id=p.tenant_id and l.workspace_id=p.workspace_id and l.id=p.backing_line_id "
                        + "join warehouse.inventory_backing b on b.tenant_id=l.tenant_id and b.workspace_id=l.workspace_id and b.id=l.backing_id "
                        + "where p.tenant_id=? and p.workspace_id=? and b.status='BACKED' and l.sku_id in (" + placeholders + ") group by l.sku_id,p.warehouse_id",
                (rs, row) -> new StockAmount(new StockKey(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)), rs.getBigDecimal(3)), args.toArray())
                .stream().collect(Collectors.toMap(StockAmount::key, StockAmount::amount, BigDecimal::add));
    }

    private BigDecimal safetyStock(UUID tenantId, UUID workspaceId, UUID warehouseId, UUID skuId) {
        return jdbc.query("select quantity from warehouse.safety_stock_policy where tenant_id=? and workspace_id=? and warehouse_id=? and sku_id=?",
                (rs, row) -> rs.getBigDecimal(1), tenantId, workspaceId, warehouseId, skuId).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    private BackingResult load(UUID tenantId, UUID workspaceId, UUID backingId) {
        List<Position> positions = jdbc.query("select l.sku_id,p.warehouse_id,p.quantity from warehouse.inventory_backing_position p join warehouse.inventory_backing_line l on l.tenant_id=p.tenant_id and l.workspace_id=p.workspace_id and l.id=p.backing_line_id where p.tenant_id=? and p.workspace_id=? and l.backing_id=? order by l.sku_id,p.warehouse_id",
                (rs, row) -> new Position(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getBigDecimal(3)), tenantId, workspaceId, backingId);
        return new BackingResult(backingId, positions);
    }

    private static Timestamp timestamp(Instant value) { return Timestamp.from(java.util.Objects.requireNonNull(value, "now")); }
    private static String normalize(String reason) { if (reason == null || reason.isBlank()) return "Commercial commitment released"; return reason.length() > 1000 ? reason.substring(0, 1000) : reason.trim(); }
    private record BackingRow(UUID id, String status) { }
    private record LotRow(UUID id, UUID skuId, UUID warehouseId, BigDecimal available) { }
    private record StockKey(UUID skuId, UUID warehouseId) { }
    private record StockAmount(StockKey key, BigDecimal amount) { }
    private record PositionPlan(RequestedLine line, UUID warehouseId, BigDecimal quantity) { }
}
