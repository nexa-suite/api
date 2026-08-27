package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogProductAvailabilityAdapter implements ProductAvailabilityPort {
    private final JdbcTemplate jdbc;

    public CatalogProductAvailabilityAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public List<Snapshot> find(CatalogScope scope, List<String> catalogItemIds) {
        List<String> ids = catalogItemIds == null ? List.of() : catalogItemIds.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(value -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        args.addAll(ids);
        Map<String, Snapshot> found = jdbc.query("select l.catalog_item_id,coalesce(sum(l.stock_quantity-l.reserved_quantity),0),min(l.expiration_date) " +
                        "from warehouse.inventory_lot l join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id " +
                        "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.id=l.zone_id " +
                        "where l.tenant_id=? and l.workspace_id=? and l.catalog_item_id in (" + placeholders + ") and l.status='AVAILABLE' and l.expiration_date>current_date and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' group by l.catalog_item_id",
                (rs, row) -> {
                    java.math.BigDecimal available = rs.getBigDecimal(2);
                    java.sql.Date expiry = rs.getDate(3);
                    boolean nearExpiry = expiry != null && !expiry.toLocalDate().isAfter(java.time.LocalDate.now().plusDays(7));
                    String status = available.signum() <= 0 ? "OUT_OF_STOCK" : available.compareTo(java.math.BigDecimal.TEN) <= 0 ? "LOW" : "AVAILABLE";
                    return new Snapshot(rs.getString(1), status, nearExpiry, Instant.now());
                }, args.toArray()).stream().collect(Collectors.toMap(Snapshot::catalogItemId, Function.identity()));
        return ids.stream().map(id -> found.getOrDefault(id, new Snapshot(id, "OUT_OF_STOCK", false, Instant.now()))).toList();
    }
}
