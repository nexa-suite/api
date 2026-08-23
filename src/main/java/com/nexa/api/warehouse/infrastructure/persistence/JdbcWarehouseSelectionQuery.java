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
        String placeholders = ids.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId));
        parameters.addAll(ids);
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("select sku_id,coalesce(sum(stock_quantity-reserved_quantity),0) from warehouse.inventory_lot "
                        + "where tenant_id=? and workspace_id=? and status='AVAILABLE' and expiration_date>=current_date "
                        + "and sku_id in (" + placeholders + ") group by sku_id",
                rs -> { while (rs.next()) result.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)); return null; },
                parameters.toArray());
        return Map.copyOf(result);
    }

    private Map<UUID, BigDecimal> availabilityAt(
            UUID tenantId, UUID workspaceId, UUID warehouseId, List<UUID> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        String placeholders = skuIds.stream().map(ignored -> "?").collect(java.util.stream.Collectors.joining(","));
        List<Object> parameters = new ArrayList<>(List.of(tenantId, workspaceId, warehouseId));
        parameters.addAll(skuIds);
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        jdbc.query("select sku_id,coalesce(sum(stock_quantity-reserved_quantity),0) from warehouse.inventory_lot "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and status='AVAILABLE' "
                        + "and expiration_date>=current_date and sku_id in (" + placeholders + ") group by sku_id",
                rs -> { while (rs.next()) result.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)); return null; },
                parameters.toArray());
        return result;
    }

    private static WarehouseReference reference(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WarehouseReference(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getInt(6), rs.getBoolean(7), rs.getBigDecimal(8), rs.getBigDecimal(9));
    }
}
