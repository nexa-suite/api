package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.port.DispatchReadinessPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads only BC-06 fulfillment, Delivery and picking facts. */
@Repository
@Profile("!test")
public class JdbcDispatchReadinessQueryAdapter implements DispatchReadinessPersistencePort {
    private static final String CANDIDATES = "select f.id fulfillment_id,f.sales_order_id,f.physical_allocation_id,"
            + "f.status fulfillment_status,f.version fulfillment_version,d.id delivery_id,d.status delivery_status,"
            + "d.version delivery_version,l.id fulfillment_line_id,l.sku_id,l.catalog_item_id,"
            + "case when ord.delivery_window_start is not null or ord.delivery_window_end is not null "
            + "then ord.delivery_window_start else planned.window_start end window_start,"
            + "case when ord.delivery_window_start is not null or ord.delivery_window_end is not null "
            + "then ord.delivery_window_end else planned.window_end end window_end,"
            + "case when ord.delivery_window_start is not null or ord.delivery_window_end is not null then 'COMMERCIAL' "
            + "when planned.id is not null then 'DISPATCH_PLAN' else null end window_source,"
            + "l.allocated_quantity,l.picked_quantity,l.unit "
            + "from logistics.fulfillment f "
            + "left join logistics.delivery d on d.tenant_id=f.tenant_id and d.workspace_id=f.workspace_id "
            + "and d.fulfillment_id=f.id "
            + "left join lateral (select selected.id,selected.delivery_window_start,selected.delivery_window_end "
            + "from logistics.dispatch_order selected where selected.tenant_id=f.tenant_id and selected.workspace_id=f.workspace_id "
            + "and ((d.dispatch_order_id is not null and selected.id=d.dispatch_order_id) "
            + "or (d.dispatch_order_id is null and selected.sales_order_id=f.sales_order_id)) "
            + "order by case when selected.id=d.dispatch_order_id then 0 else 1 end,selected.created_at desc,selected.id desc limit 1) ord on true "
            + "left join lateral (select p.id,p.window_start,p.window_end from logistics.fulfillment_dispatch_window_plan p "
            + "where p.tenant_id=f.tenant_id and p.workspace_id=f.workspace_id and p.fulfillment_id=f.id "
            + "order by p.revision desc limit 1) planned on true "
            + "left join logistics.fulfillment_line l on l.tenant_id=f.tenant_id "
            + "and l.workspace_id=f.workspace_id and l.fulfillment_id=f.id "
            + "where f.tenant_id=? and f.workspace_id=? and f.status not in ('COMPLETED','CANCELLED')";

    private final JdbcTemplate jdbc;

    public JdbcDispatchReadinessQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PreparedFulfillment> candidates(UUID tenantId, UUID workspaceId) {
        return query(tenantId, workspaceId, null);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PreparedFulfillment> find(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        List<PreparedFulfillment> matches = query(tenantId, workspaceId, fulfillmentId);
        return matches.stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PickingEvidence> pickingEvidence(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        return jdbc.query("select l.fulfillment_line_id,r.status result_status,l.quantity,"
                        + "l.physical_allocation_line_id,l.lot_id,l.warehouse_id "
                        + "from logistics.picking_result_line l "
                        + "join logistics.picking_result r on r.tenant_id=l.tenant_id "
                        + "and r.workspace_id=l.workspace_id and r.id=l.picking_result_id "
                        + "where l.tenant_id=? and l.workspace_id=? and r.fulfillment_id=? "
                        + "order by r.created_at,l.id",
                (rs, row) -> new PickingEvidence(
                        rs.getObject("fulfillment_line_id", UUID.class),
                        rs.getString("result_status"),
                        rs.getBigDecimal("quantity"),
                        rs.getObject("physical_allocation_line_id", UUID.class),
                        rs.getObject("lot_id", UUID.class),
                        rs.getObject("warehouse_id", UUID.class)),
                tenantId, workspaceId, fulfillmentId);
    }

    private List<PreparedFulfillment> query(UUID tenantId, UUID workspaceId, UUID fulfillmentId) {
        String sql = CANDIDATES + (fulfillmentId == null ? "" : " and f.id=?")
                + " order by f.updated_at desc,f.id desc,l.id";
        List<Object> arguments = new ArrayList<>(List.of(tenantId, workspaceId));
        if (fulfillmentId != null) arguments.add(fulfillmentId);
        Map<UUID, CandidateBuilder> grouped = new LinkedHashMap<>();
        jdbc.query(sql, (RowCallbackHandler) rs -> addRow(grouped, rs), arguments.toArray());
        return grouped.values().stream().map(CandidateBuilder::build).toList();
    }

    private static void addRow(Map<UUID, CandidateBuilder> grouped, ResultSet rs) throws SQLException {
        UUID fulfillmentId = rs.getObject("fulfillment_id", UUID.class);
        CandidateBuilder candidate = grouped.computeIfAbsent(fulfillmentId, ignored -> {
            try {
                UUID deliveryId = rs.getObject("delivery_id", UUID.class);
                return new CandidateBuilder(
                        fulfillmentId,
                        rs.getObject("sales_order_id", UUID.class),
                        rs.getObject("physical_allocation_id", UUID.class),
                        rs.getString("fulfillment_status"),
                        rs.getLong("fulfillment_version"),
                        deliveryId,
                        deliveryId == null ? null : rs.getString("delivery_status"),
                        deliveryId == null ? null : rs.getLong("delivery_version"),
                        instant(rs.getTimestamp("window_start")), instant(rs.getTimestamp("window_end")),
                        rs.getString("window_source"));
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not read dispatch-readiness candidate", exception);
            }
        });
        UUID lineId = rs.getObject("fulfillment_line_id", UUID.class);
        if (lineId != null) {
            candidate.lines.add(new FulfillmentLine(
                    lineId,
                    rs.getObject("sku_id", UUID.class),
                    rs.getString("catalog_item_id"),
                    rs.getBigDecimal("allocated_quantity"),
                    rs.getBigDecimal("picked_quantity"),
                    rs.getString("unit")));
        }
    }

    private static final class CandidateBuilder {
        private final UUID id;
        private final UUID salesOrderId;
        private final UUID physicalAllocationId;
        private final String status;
        private final long version;
        private final UUID deliveryId;
        private final String deliveryStatus;
        private final Long deliveryVersion;
        private final Instant windowStart;
        private final Instant windowEnd;
        private final String windowSource;
        private final List<FulfillmentLine> lines = new ArrayList<>();

        private CandidateBuilder(UUID id, UUID salesOrderId, UUID physicalAllocationId,
                                 String status, long version, UUID deliveryId,
                                 String deliveryStatus, Long deliveryVersion,
                                 Instant windowStart, Instant windowEnd, String windowSource) {
            this.id = id;
            this.salesOrderId = salesOrderId;
            this.physicalAllocationId = physicalAllocationId;
            this.status = status;
            this.version = version;
            this.deliveryId = deliveryId;
            this.deliveryStatus = deliveryStatus;
            this.deliveryVersion = deliveryVersion;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.windowSource = windowSource;
        }

        private PreparedFulfillment build() {
            return new PreparedFulfillment(id, salesOrderId, physicalAllocationId,
                    status, version, deliveryId, deliveryStatus, deliveryVersion,
                    windowStart, windowEnd, windowSource, lines);
        }
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
