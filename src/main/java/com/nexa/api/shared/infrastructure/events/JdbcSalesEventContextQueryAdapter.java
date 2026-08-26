package com.nexa.api.shared.infrastructure.events;

import com.nexa.api.salescommitment.application.port.out.SalesEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** ACL adapter translating the sales read model into event published language. */
@Repository
@Profile("!test")
public class JdbcSalesEventContextQueryAdapter implements SalesEventContextQueryPort {
    private final JdbcTemplate jdbc;

    public JdbcSalesEventContextQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId,
                                                                   UUID purchaseRequestId) {
        return jdbc.query("select id,client_account_id,version from sales.purchase_request "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next()
                        ? Optional.of(new PurchaseRequestSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version")))
                        : Optional.empty(), tenantId, workspaceId, purchaseRequestId);
    }

    @Override
    public Optional<SalesOrderSnapshot> findSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
        return jdbc.query("select id,client_account_id,version from sales.sales_order "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next()
                        ? Optional.of(new SalesOrderSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version")))
                        : Optional.empty(), tenantId, workspaceId, salesOrderId);
    }

    @Override
    public Optional<SalesOrderSnapshot> findSalesOrderBySourcePurchaseRequest(UUID tenantId, UUID workspaceId,
                                                                                UUID purchaseRequestId) {
        List<SalesOrderSnapshot> matches = jdbc.query("select id,client_account_id,version from sales.sales_order "
                        + "where tenant_id=? and workspace_id=? and source_purchase_request_id=?",
                (rs, row) -> new SalesOrderSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version")),
                tenantId, workspaceId, purchaseRequestId);
        return uniqueOrEmpty(matches, "sales order source purchase request");
    }

    @Override
    public Set<UUID> findBuyerMembershipIds(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
        return Set.copyOf(jdbc.query("select distinct cam.workspace_membership_id "
                        + "from sales.client_account_membership cam "
                        + "join tenant_management.workspace_membership m "
                        + "on m.workspace_id=cam.workspace_id and m.id=cam.workspace_membership_id "
                        + "where cam.tenant_id=? and cam.workspace_id=? and cam.client_account_id=? "
                        + "and m.status='ACTIVE' and m.membership_type='BUYER'",
                (rs, row) -> rs.getObject(1, UUID.class), tenantId, workspaceId, clientAccountId));
    }

    private static <T> Optional<T> uniqueOrEmpty(List<T> matches, String description) {
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous " + description + " context");
        return matches.stream().findFirst();
    }
}
