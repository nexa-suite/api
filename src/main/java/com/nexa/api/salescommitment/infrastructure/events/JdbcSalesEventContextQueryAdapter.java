package com.nexa.api.salescommitment.infrastructure.events;

import com.nexa.api.salescommitment.application.publicapi.SalesEventContextQueryPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerMembershipQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WorkforceDirectory;
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
    private final CustomerMembershipQuery customerMemberships;
    private final WorkforceDirectory workforce;

    public JdbcSalesEventContextQueryAdapter(JdbcTemplate jdbc, CustomerMembershipQuery customerMemberships,
                                             WorkforceDirectory workforce) {
        this.jdbc = jdbc;
        this.customerMemberships = customerMemberships;
        this.workforce = workforce;
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
        return jdbc.query("select id,client_account_id,version,commercial_commitment_id from sales.sales_order "
                        + "where tenant_id=? and workspace_id=? and id=?",
                rs -> rs.next()
                        ? Optional.of(new SalesOrderSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version"),
                        rs.getObject("commercial_commitment_id", UUID.class)))
                        : Optional.empty(), tenantId, workspaceId, salesOrderId);
    }

    @Override
    public Optional<SalesOrderSnapshot> findSalesOrderBySourcePurchaseRequest(UUID tenantId, UUID workspaceId,
                                                                                UUID purchaseRequestId) {
        List<SalesOrderSnapshot> matches = jdbc.query("select id,client_account_id,version,commercial_commitment_id from sales.sales_order "
                        + "where tenant_id=? and workspace_id=? and source_purchase_request_id=?",
                (rs, row) -> new SalesOrderSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version"),
                        rs.getObject("commercial_commitment_id", UUID.class)),
                tenantId, workspaceId, purchaseRequestId);
        return uniqueOrEmpty(matches, "sales order source purchase request");
    }

    @Override
    public Set<UUID> findBuyerMembershipIds(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
        List<UUID> relatedMembershipIds = customerMemberships.findMembershipIds(tenantId, workspaceId, clientAccountId);
        if (relatedMembershipIds.isEmpty()) return Set.of();
        return workforce.filterActiveBuyerMembershipIds(tenantId, workspaceId, relatedMembershipIds);
    }

    private static <T> Optional<T> uniqueOrEmpty(List<T> matches, String description) {
        if (matches.size() > 1) throw new IllegalStateException("Ambiguous " + description + " context");
        return matches.stream().findFirst();
    }
}
