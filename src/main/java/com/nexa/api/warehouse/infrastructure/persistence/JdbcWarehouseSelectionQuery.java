package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.warehouse.application.publicapi.WarehouseSelectionQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcWarehouseSelectionQuery implements WarehouseSelectionQuery {
    private static final String SELECT = "select w.id,w.code,w.name,coalesce(nullif(w.address,''),'Warehouse address not configured'),"
            + "coalesce(c.service_status,'OPERATIONAL'),coalesce(c.priority,0),coalesce(c.preferred,false),c.latitude,c.longitude "
            + "from warehouse.warehouse w left join warehouse.warehouse_service_configuration c "
            + "on c.warehouse_id=w.id and c.tenant_id=w.tenant_id and c.workspace_id=w.workspace_id "
            + "where w.tenant_id=? and w.workspace_id=? and w.status='ACTIVE' "
            + "and coalesce(c.service_status,'OPERATIONAL')='OPERATIONAL'";

    private final JdbcTemplate jdbc;

    public JdbcWarehouseSelectionQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<WarehouseReference> findOperational(UUID tenantId, UUID workspaceId, UUID warehouseId) {
        return jdbc.query(SELECT + " and w.id=?", (rs, ignored) -> reference(rs), tenantId, workspaceId, warehouseId)
                .stream().findFirst();
    }

    @Override
    public Optional<WarehouseReference> findPrimaryOperational(UUID tenantId, UUID workspaceId) {
        return jdbc.query(SELECT + " order by coalesce(c.preferred,false) desc,coalesce(c.priority,0) desc,w.code,w.id limit 1",
                (rs, ignored) -> reference(rs), tenantId, workspaceId).stream().findFirst();
    }

    @Override
    public Optional<WarehouseReference> findFulfillable(
            UUID tenantId, UUID workspaceId, Map<UUID, BigDecimal> requestedQuantities) {
        if (requestedQuantities == null || requestedQuantities.isEmpty()) return Optional.empty();
        List<WarehouseReference> candidates = jdbc.query(SELECT
                        + " order by coalesce(c.preferred,false) desc,coalesce(c.priority,0) desc,w.id",
                (rs, ignored) -> reference(rs), tenantId, workspaceId);
        for (WarehouseReference candidate : candidates) {
            Map<UUID, BigDecimal> available = availabilityAt(tenantId, workspaceId, candidate.id(),
                    requestedQuantities.keySet().stream().toList());
            if (requestedQuantities.entrySet().stream()
                    .allMatch(entry -> available.getOrDefault(entry.getKey(), BigDecimal.ZERO)
                            .compareTo(entry.getValue()) >= 0)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<UUID, BigDecimal> availability(UUID tenantId, UUID workspaceId, List<UUID> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) return Map.of();
        List<UUID> ids = skuIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        return available(tenantId, workspaceId, null, ids);
    }

    private Map<UUID, BigDecimal> availabilityAt(
        UUID tenantId, UUID workspaceId, UUID warehouseId, List<UUID> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        return available(tenantId, workspaceId, warehouseId, skuIds);
    }

    private Map<UUID, BigDecimal> available(UUID tenantId, UUID workspaceId, UUID warehouseId, List<UUID> skuIds) {
        String placeholders = skuIds.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        String warehousePredicate = warehouseId == null ? "" : " and l.warehouse_id=?";
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        if (warehouseId != null) parameters.add(warehouseId);
        parameters.addAll(skuIds);
        parameters.add(tenantId);
        parameters.add(workspaceId);
        parameters.addAll(skuIds);
        parameters.add(tenantId);
        parameters.add(workspaceId);
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("with eligible as (select l.sku_id,l.warehouse_id,coalesce(sum(l.stock_quantity-l.reserved_quantity),0) available "
                        + "from warehouse.inventory_lot l join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "where l.tenant_id=? and l.workspace_id=?" + warehousePredicate
                        + " and l.status='AVAILABLE' and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' "
                        + "and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' and l.sku_id in (" + placeholders + ") "
                        + "group by l.sku_id,l.warehouse_id), active_backing as ("
                        + "select line.sku_id,position.warehouse_id,coalesce(sum(position.quantity),0) amount "
                        + "from warehouse.inventory_backing_position position "
                        + "join warehouse.inventory_backing_line line on line.tenant_id=position.tenant_id and line.workspace_id=position.workspace_id and line.id=position.backing_line_id "
                        + "join warehouse.inventory_backing backing on backing.tenant_id=line.tenant_id and backing.workspace_id=line.workspace_id and backing.id=line.backing_id "
                        + "where position.tenant_id=? and position.workspace_id=? and backing.status='BACKED' and line.sku_id in (" + placeholders + ") "
                        + "group by line.sku_id,position.warehouse_id), capacity as ("
                        + "select eligible.sku_id,greatest(eligible.available-coalesce(safety.quantity,0)-coalesce(active_backing.amount,0),0) amount "
                        + "from eligible left join warehouse.safety_stock_policy safety on safety.tenant_id=? and safety.workspace_id=? "
                        + "and safety.warehouse_id=eligible.warehouse_id and safety.sku_id=eligible.sku_id "
                        + "left join active_backing on active_backing.sku_id=eligible.sku_id and active_backing.warehouse_id=eligible.warehouse_id) "
                        + "select sku_id,coalesce(sum(amount),0) from capacity group by sku_id",
                rs -> { while (rs.next()) result.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)); return null; },
                parameters.toArray());
        return result;
    }

    private static WarehouseReference reference(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WarehouseReference(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getBoolean(7), rs.getBigDecimal(8), rs.getBigDecimal(9));
    }
}
