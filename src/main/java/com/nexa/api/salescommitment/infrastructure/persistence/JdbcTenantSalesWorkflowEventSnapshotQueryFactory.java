package com.nexa.api.salescommitment.infrastructure.persistence;

import com.nexa.api.salescommitment.application.publicapi.SalesWorkflowEventSnapshotQuery;
import com.nexa.api.salescommitment.tenantdatabase.TenantSalesWorkflowEventSnapshotQueryFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Creates narrow Sales event snapshots over the exact router-owned Tenant session. */
@Component
@Profile("!test")
public final class JdbcTenantSalesWorkflowEventSnapshotQueryFactory
        implements TenantSalesWorkflowEventSnapshotQueryFactory {
    @Override
    public SalesWorkflowEventSnapshotQuery bindTo(JdbcTemplate tenantJdbc) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        return new SalesWorkflowEventSnapshotQuery() {
            @Override
            public Optional<PurchaseRequestSnapshot> findPurchaseRequest(UUID tenantId, UUID workspaceId,
                    UUID purchaseRequestId) {
                return jdbc.query("select id,client_account_id,version from sales.purchase_request "
                                + "where tenant_id=? and workspace_id=? and id=?",
                        rs -> rs.next() ? Optional.of(new PurchaseRequestSnapshot(rs.getObject("id", UUID.class),
                                rs.getObject("client_account_id", UUID.class), rs.getLong("version")))
                                : Optional.empty(), tenantId, workspaceId, purchaseRequestId);
            }

            @Override
            public Optional<SalesOrderSnapshot> findSalesOrder(UUID tenantId, UUID workspaceId, UUID salesOrderId) {
                return jdbc.query("select id,client_account_id,version,commercial_commitment_id "
                                + "from sales.sales_order where tenant_id=? and workspace_id=? and id=?",
                        rs -> rs.next() ? Optional.of(salesOrder(rs)) : Optional.empty(),
                        tenantId, workspaceId, salesOrderId);
            }

            @Override
            public Optional<SalesOrderSnapshot> findSalesOrderBySourcePurchaseRequest(UUID tenantId,
                    UUID workspaceId, UUID purchaseRequestId) {
                List<SalesOrderSnapshot> matches = jdbc.query("select id,client_account_id,version,commercial_commitment_id "
                                + "from sales.sales_order where tenant_id=? and workspace_id=? "
                                + "and source_purchase_request_id=?",
                        (rs, row) -> salesOrder(rs), tenantId, workspaceId, purchaseRequestId);
                if (matches.size() > 1) throw new IllegalStateException("Ambiguous Sales order source purchase request");
                return matches.stream().findFirst();
            }

            private SalesOrderSnapshot salesOrder(java.sql.ResultSet rs) throws java.sql.SQLException {
                return new SalesOrderSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("client_account_id", UUID.class), rs.getLong("version"),
                        rs.getObject("commercial_commitment_id", UUID.class));
            }
        };
    }
}
