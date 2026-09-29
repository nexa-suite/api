package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesDocumentSourceQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sales-owned, scoped source facts for Business Documents. */
@Repository
@Profile("!test")
public class JdbcSalesDocumentSourceQuery implements SalesDocumentSourceQuery {
    private final JdbcTemplate jdbc;

    public JdbcSalesDocumentSourceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<SalesOrderSnapshot> findOrder(UUID tenantId, UUID workspaceId, UUID orderId) {
        List<SalesOrderHeader> rows = jdbc.query(
                "select id,client_account_id,number,created_at,requested_delivery_date,status,delivery_snapshot,"
                        + "delivery_address_snapshot::text delivery_address_snapshot,route_snapshot::text route_snapshot,"
                        + "warehouse_selection_snapshot::text warehouse_selection_snapshot,payment_option,notes,currency,total_amount "
                        + "from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new SalesOrderHeader(rs), tenantId, workspaceId, orderId);
        if (rows.isEmpty()) return Optional.empty();
        SalesOrderHeader header = rows.get(0);
        List<Line> lines = jdbc.query(
                "select sku_id,product_family_id,sku_code_snapshot,product_family_code_snapshot,catalog_item_id,"
                        + "item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,line_subtotal,unit_price_currency "
                        + "from sales.sales_order_line where sales_order_id=? order by created_at,id",
                JdbcSalesDocumentSourceQuery::line, orderId);
        return Optional.of(new SalesOrderSnapshot(header.id(), header.customerAccountId(), header.number(),
                header.createdAt(), header.requestedDeliveryDate(), header.status(), header.deliverySnapshot(),
                header.deliveryAddressSnapshot(), header.routeSnapshot(), header.warehouseSelectionSnapshot(),
                header.paymentOption(), header.notes(), header.currency(), header.totalAmount(), lines));
    }

    @Override
    public Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId, UUID requestId) {
        List<PurchaseRequestHeader> rows = jdbc.query(
                "select id,client_account_id,code,created_at,requested_delivery_date,status,payment_option,comments,review_note,"
                        + "delivery_address_snapshot::text delivery_address_snapshot,route_snapshot::text route_snapshot,"
                        + "warehouse_selection_snapshot::text warehouse_selection_snapshot "
                        + "from sales.purchase_request where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> new PurchaseRequestHeader(rs), tenantId, workspaceId, requestId);
        if (rows.isEmpty()) return Optional.empty();
        PurchaseRequestHeader header = rows.get(0);
        List<Line> lines = jdbc.query(
                "select sku_id,product_family_id,sku_code_snapshot,product_family_code_snapshot,catalog_item_id,"
                        + "item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,"
                        + "(quantity*unit_price_amount) line_subtotal,unit_price_currency "
                        + "from sales.purchase_request_line where purchase_request_id=? order by created_at,id",
                JdbcSalesDocumentSourceQuery::line, requestId);
        return Optional.of(new PurchaseRequestSnapshot(header.id(), header.customerAccountId(), header.code(),
                header.createdAt(), header.requestedDeliveryDate(), header.status(), header.paymentOption(),
                header.comments(), header.reviewNote(), header.deliveryAddressSnapshot(), header.routeSnapshot(),
                header.warehouseSelectionSnapshot(), lines));
    }

    private static Line line(ResultSet rs, int row) throws SQLException {
        return new Line(rs.getObject("sku_id", UUID.class), rs.getObject("product_family_id", UUID.class),
                rs.getString("sku_code_snapshot"), rs.getString("product_family_code_snapshot"),
                rs.getString("catalog_item_id"), rs.getString("item_name_snapshot"),
                rs.getString("presentation_snapshot"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("unit_price_amount"), rs.getBigDecimal("line_subtotal"),
                rs.getString("unit_price_currency"));
    }

    private record SalesOrderHeader(UUID id, UUID customerAccountId, String number, Instant createdAt,
                                    LocalDate requestedDeliveryDate, String status, String deliverySnapshot,
                                    String deliveryAddressSnapshot, String routeSnapshot,
                                    String warehouseSelectionSnapshot, String paymentOption, String notes,
                                    String currency, java.math.BigDecimal totalAmount) {
        private SalesOrderHeader(ResultSet rs) throws SQLException {
            this(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class),
                    rs.getString("number"), rs.getTimestamp("created_at").toInstant(), date(rs, "requested_delivery_date"),
                    rs.getString("status"), rs.getString("delivery_snapshot"), rs.getString("delivery_address_snapshot"),
                    rs.getString("route_snapshot"), rs.getString("warehouse_selection_snapshot"),
                    rs.getString("payment_option"), rs.getString("notes"), rs.getString("currency"),
                    rs.getBigDecimal("total_amount"));
        }
    }

    private record PurchaseRequestHeader(UUID id, UUID customerAccountId, String code, Instant createdAt,
                                         LocalDate requestedDeliveryDate, String status, String paymentOption,
                                         String comments, String reviewNote, String deliveryAddressSnapshot,
                                         String routeSnapshot, String warehouseSelectionSnapshot) {
        private PurchaseRequestHeader(ResultSet rs) throws SQLException {
            this(rs.getObject("id", UUID.class), rs.getObject("client_account_id", UUID.class),
                    rs.getString("code"), rs.getTimestamp("created_at").toInstant(), date(rs, "requested_delivery_date"),
                    rs.getString("status"), rs.getString("payment_option"), rs.getString("comments"),
                    rs.getString("review_note"), rs.getString("delivery_address_snapshot"),
                    rs.getString("route_snapshot"), rs.getString("warehouse_selection_snapshot"));
        }
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }
}
