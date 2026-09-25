package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import com.nexa.api.catalogcommercialpolicy.application.port.out.ProductAvailabilityPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        List<String> ids = catalogItemIds == null ? List.of() : catalogItemIds.stream()
                .filter(value -> value != null && !value.isBlank()).distinct().toList();
        if (ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(value -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.workspaceId()));
        args.addAll(ids);
        Instant asOf = Instant.now();
        List<Snapshot> found = jdbc.query("with scoped_sku as ("
                        + "select id,legacy_catalog_item_id,temperature_min,temperature_max "
                        + "from catalog_management.sellable_sku where tenant_id=? and workspace_id=? "
                        + "and status='ACTIVE' and legacy_catalog_item_id in (" + placeholders + ")), "
                        + "eligible_lot as ("
                        + "select s.id sku_id,l.warehouse_id,l.expiration_date,l.stock_quantity-l.reserved_quantity quantity "
                        + "from scoped_sku s join warehouse.inventory_lot l on l.tenant_id=? and l.workspace_id=? and l.sku_id=s.id "
                        + "join warehouse.warehouse w on w.tenant_id=l.tenant_id and w.workspace_id=l.workspace_id and w.id=l.warehouse_id "
                        + "join warehouse.storage_zone z on z.tenant_id=l.tenant_id and z.workspace_id=l.workspace_id and z.warehouse_id=l.warehouse_id and z.id=l.zone_id "
                        + "left join warehouse.warehouse_service_configuration service on service.tenant_id=l.tenant_id and service.workspace_id=l.workspace_id and service.warehouse_id=l.warehouse_id "
                        + "where l.status='AVAILABLE' and l.expiration_date>current_date and l.stock_quantity>l.reserved_quantity "
                        + "and w.status='ACTIVE' and z.status='ACTIVE' and z.zone_type<>'QUARANTINE' "
                        + "and coalesce(service.service_status,'OPERATIONAL')='OPERATIONAL' "
                        + "and (s.temperature_min is null or (z.temperature_min is not null and z.temperature_min<=s.temperature_min)) "
                        + "and (s.temperature_max is null or (z.temperature_max is not null and z.temperature_max>=s.temperature_max)) "
                        + "and ((s.temperature_min is null and s.temperature_max is null) or (l.temperature_value is not null "
                        + "and (s.temperature_min is null or l.temperature_value>=s.temperature_min) "
                        + "and (s.temperature_max is null or l.temperature_value<=s.temperature_max))) "
                        + "and not exists (select 1 from warehouse.inventory_temperature_evaluation evaluation "
                        + "where evaluation.tenant_id=l.tenant_id and evaluation.workspace_id=l.workspace_id and evaluation.lot_id=l.id "
                        + "and evaluation.status='OPEN' and evaluation.disposition='HOLD') "
                        + "and coalesce((select disposition.disposition from warehouse.inventory_lot_disposition disposition "
                        + "where disposition.tenant_id=l.tenant_id and disposition.workspace_id=l.workspace_id and disposition.lot_id=l.id "
                        + "order by disposition.created_at desc,disposition.id desc limit 1),'RELEASE') not in ('HOLD','WASTE','RETURN_TO_SUPPLIER')), "
                        + "eligible_by_warehouse as (select sku_id,warehouse_id,sum(quantity) quantity,min(expiration_date) earliest_expiration "
                        + "from eligible_lot group by sku_id,warehouse_id), "
                        + "active_backing as (select line.sku_id,position.warehouse_id,sum(position.quantity) quantity "
                        + "from warehouse.inventory_backing_position position "
                        + "join warehouse.inventory_backing_line line on line.tenant_id=position.tenant_id and line.workspace_id=position.workspace_id and line.id=position.backing_line_id "
                        + "join warehouse.inventory_backing backing on backing.tenant_id=line.tenant_id and backing.workspace_id=line.workspace_id and backing.id=line.backing_id "
                        + "where position.tenant_id=? and position.workspace_id=? and backing.status='BACKED' "
                        + "group by line.sku_id,position.warehouse_id), "
                        + "capacity as (select eligible.sku_id,eligible.earliest_expiration, "
                        + "greatest(0::numeric,eligible.quantity-coalesce(safety.quantity,0)-coalesce(backing.quantity,0)) sellable_quantity "
                        + "from eligible_by_warehouse eligible "
                        + "left join warehouse.safety_stock_policy safety on safety.tenant_id=? and safety.workspace_id=? "
                        + "and safety.warehouse_id=eligible.warehouse_id and safety.sku_id=eligible.sku_id "
                        + "left join active_backing backing on backing.sku_id=eligible.sku_id and backing.warehouse_id=eligible.warehouse_id), "
                        + "availability as (select s.legacy_catalog_item_id,coalesce(sum(c.sellable_quantity),0) sellable_quantity, "
                        + "min(c.earliest_expiration) filter (where c.sellable_quantity>0) earliest_expiration "
                        + "from scoped_sku s left join capacity c on c.sku_id=s.id group by s.legacy_catalog_item_id) "
                        + "select legacy_catalog_item_id,sellable_quantity,earliest_expiration from availability",
                (rs, row) -> {
                    BigDecimal available = rs.getBigDecimal("sellable_quantity");
                    java.sql.Date expiry = rs.getDate("earliest_expiration");
                    boolean nearExpiry = expiry != null && !expiry.toLocalDate().isAfter(LocalDate.now().plusDays(7));
                    String status = available.signum() <= 0 ? "OUT_OF_STOCK"
                            : available.compareTo(BigDecimal.TEN) <= 0 ? "LOW" : "AVAILABLE";
                    return new Snapshot(rs.getString("legacy_catalog_item_id"), status, nearExpiry, asOf, available);
                },
                // The first two scope values select catalog SKUs; inventory and policy data
                // are then constrained to the same request scope throughout the query.
                mergeArgs(args, scope));
        Map<String, Snapshot> byId = found.stream().collect(Collectors.toMap(Snapshot::catalogItemId, Function.identity()));
        return ids.stream().map(id -> byId.getOrDefault(id,
                new Snapshot(id, "OUT_OF_STOCK", false, asOf, BigDecimal.ZERO))).toList();
    }

    private static Object[] mergeArgs(List<Object> catalogArgs, CatalogScope scope) {
        List<Object> args = new ArrayList<>(catalogArgs);
        args.add(scope.tenantId());
        args.add(scope.workspaceId());
        args.add(scope.tenantId());
        args.add(scope.workspaceId());
        args.add(scope.tenantId());
        args.add(scope.workspaceId());
        return args.toArray();
    }
}
