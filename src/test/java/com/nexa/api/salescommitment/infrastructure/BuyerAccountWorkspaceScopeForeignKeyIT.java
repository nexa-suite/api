package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class BuyerAccountWorkspaceScopeForeignKeyIT extends PostgresIntegrationSupport {

    @Test
    void buyerAssociationAndPurchaseRequestRejectAccountFromAnotherTenantInCentralAndTenantDatabases() {
        UUID tenantA = UUID.fromString(tenantId());
        UUID workspaceA = UUID.fromString(workspaceId());
        UUID tenantB = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID accountA = UUID.randomUUID();
        UUID accountB = UUID.randomUUID();
        UUID membershipA = UUID.randomUUID();
        UUID membershipB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID requestA = UUID.randomUUID();
        UUID rejectedRequestA = UUID.randomUUID();
        UUID requestB = UUID.randomUUID();
        UUID rejectedRequestB = UUID.randomUUID();
        String suffixA = accountA.toString().replace("-", "").substring(0, 12).toUpperCase();
        String suffixB = accountB.toString().replace("-", "").substring(0, 12).toUpperCase();
        JdbcTemplate tenantBDatabase = null;

        try {
            insertCentralTenantWorkspace(tenantB, workspaceB);
            insertCentralMembership(userA, membershipA, tenantA, workspaceA, "buyer-fk-a-" + suffixA);
            insertCentralMembership(userB, membershipB, tenantB, workspaceB, "buyer-fk-b-" + suffixB);
            insertBuyerAccount(jdbc, accountA, tenantA, workspaceA, suffixA);
            insertBuyerAccount(jdbc, accountB, tenantB, workspaceB, suffixB);

            provisionTenantBusinessDatabase(tenantB, workspaceB);
            tenantBDatabase = tenantJdbcFor(tenantB);
            insertBuyerAccount(tenantJdbc(), accountA, tenantA, workspaceA, suffixA);
            insertBuyerAccount(tenantBDatabase, accountB, tenantB, workspaceB, suffixB);

            assertScopedBuyerLinks(jdbc, tenantA, workspaceA, accountA, membershipA, accountB, membershipB,
                    requestA, rejectedRequestA, "fk_client_membership_scope_account",
                    "fk_purchase_request_scope_account");
            assertScopedBuyerLinks(jdbc, tenantB, workspaceB, accountB, membershipB, accountA, membershipA,
                    requestB, rejectedRequestB, "fk_client_membership_scope_account",
                    "fk_purchase_request_scope_account");
            assertScopedBuyerLinks(tenantJdbc(), tenantA, workspaceA, accountA, membershipA, accountB, membershipB,
                    requestA, rejectedRequestA, "fk_client_account_membership_scope_account", "fk_purchase_request_account");
            assertScopedBuyerLinks(tenantBDatabase, tenantB, workspaceB, accountB, membershipB, accountA, membershipA,
                    requestB, rejectedRequestB, "fk_client_account_membership_scope_account", "fk_purchase_request_account");
        } finally {
            if (tenantBDatabase != null) {
                deleteTenantBuyerFixtures(tenantBDatabase, tenantB, workspaceB, accountB, requestB,
                        rejectedRequestB, true);
            }
            deleteTenantBuyerFixtures(tenantJdbc(), tenantA, workspaceA, accountA, requestA,
                    rejectedRequestA, false);
            deleteCentralBuyerFixtures(tenantB, workspaceB, accountA, accountB, membershipA, membershipB,
                    userA, userB, requestA, rejectedRequestA, requestB, rejectedRequestB);
        }
    }

    private void insertCentralTenantWorkspace(UUID tenant, UUID workspace) {
        String tenantSlug = "buyer-fk-tenant-" + tenant.toString().replace("-", "");
        String workspaceSlug = "buyer-fk-" + workspace.toString().replace("-", "");
        jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) " +
                        "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                tenant, "Buyer FK scope fixture", tenantSlug);
        jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) " +
                        "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                workspace, tenant, "Buyer FK scope fixture", workspaceSlug);
    }

    private void insertCentralMembership(UUID user, UUID membership, UUID tenant, UUID workspace, String suffix) {
        String email = suffix + "@example.test";
        String username = suffix.replace('-', '_');
        jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name,"+
                        "preferred_language,status,created_at,updated_at,version) "+
                        "values (?,?,?,?,?,?,'en','ACTIVE',current_timestamp,current_timestamp,0)",
                user, email, email, username, username, "Buyer FK fixture");
        jdbc.update("insert into tenant_management.workspace_membership "+
                        "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) "+
                        "values (?,?,?,?,'BUYER','ACTIVE',current_timestamp,current_timestamp,0)",
                membership, tenant, workspace, user);
    }

    private static void insertBuyerAccount(JdbcTemplate database, UUID account, UUID tenant, UUID workspace,
            String suffix) {
        Timestamp now = Timestamp.from(Instant.now());
        String taxDigits = String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(account.getLeastSignificantBits(), 1_000_000_000L));
        database.update("insert into sales.client_account "+
                        "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"+
                        "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"+
                        "delivery_profile,payment_condition,status,created_at,updated_at,version) "+
                        "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                account, tenant, workspace, "FK-" + suffix, "FK Scope Buyer " + suffix,
                "FK Scope Buyer " + suffix, "PE", "RUC", "20" + taxDigits, "FK-" + suffix,
                "Fixture Contact", "buyer-fk-" + suffix.toLowerCase() + "@example.test", "+51999999999",
                "Fixture delivery profile", "NET_30", "ACTIVE", now, now);
    }

    private static void assertScopedBuyerLinks(JdbcTemplate database, UUID tenant, UUID workspace,
            UUID account, UUID buyerMembership, UUID foreignAccount, UUID foreignMembership,
            UUID request, UUID rejectedRequest,
            String membershipConstraint, String requestConstraint) {
        database.update("insert into sales.client_account_membership "+
                        "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "+
                        "values (?,?,?,?,current_timestamp)", account, buyerMembership, tenant, workspace);
        assertThat(database.queryForObject("select count(*) from sales.client_account_membership "+
                        "where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=?",
                Integer.class, tenant, workspace, account, buyerMembership)).isEqualTo(1);
        assertForeignKeyViolation(() -> database.update("insert into sales.client_account_membership "+
                        "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) "+
                        "values (?,?,?,?,current_timestamp)", foreignAccount, foreignMembership, tenant, workspace),
                membershipConstraint);

        insertPurchaseRequest(database, request, tenant, workspace, account, buyerMembership);
        assertThat(database.queryForObject("select count(*) from sales.purchase_request "+
                        "where tenant_id=? and workspace_id=? and id=? and client_account_id=?",
                Integer.class, tenant, workspace, request, account)).isEqualTo(1);
        assertForeignKeyViolation(() -> insertPurchaseRequest(database, rejectedRequest, tenant, workspace,
                foreignAccount, buyerMembership), requestConstraint);
    }

    private static void insertPurchaseRequest(JdbcTemplate database, UUID request, UUID tenant, UUID workspace,
            UUID account, UUID buyerMembership) {
        database.update("insert into sales.purchase_request "+
                        "(id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,created_at,updated_at) "+
                        "values (?,?,?,?,?,?,'DRAFT','NORMAL',current_timestamp,current_timestamp)",
                request, tenant, workspace, account, buyerMembership, "FK-" + request.toString().substring(0, 8));
    }

    private static void assertForeignKeyViolation(ThrowingRunnable action, String expectedConstraint) {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class, action::run,
                "cross-Tenant account reference must fail at " + expectedConstraint);
        SQLException sqlException = findSqlException(failure);
        assertNotNull(sqlException, "PostgreSQL must report the failed FK");
        assertThat(sqlException.getSQLState()).isEqualTo("23503");
        assertThat(((PSQLException) sqlException).getServerErrorMessage().getConstraint())
                .isEqualTo(expectedConstraint);
    }

    private static SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException;
        }
        return null;
    }

    private static void deleteTenantBuyerFixtures(JdbcTemplate database, UUID tenant, UUID workspace,
            UUID account, UUID request, UUID rejectedRequest, boolean removeScopeAnchor) {
        database.update("delete from sales.purchase_request where id in (?,?)", request, rejectedRequest);
        database.update("delete from sales.client_account_membership where client_account_id=?", account);
        database.update("delete from sales.client_account where id=?", account);
        if (removeScopeAnchor) {
            database.update("delete from nexa_platform.tenant_workspace_scope_anchor where tenant_id=? and workspace_id=?",
                    tenant, workspace);
        }
    }

    private void deleteCentralBuyerFixtures(UUID tenant, UUID workspace, UUID accountA, UUID accountB,
            UUID membershipA, UUID membershipB, UUID userA, UUID userB,
            UUID requestA, UUID rejectedRequestA, UUID requestB, UUID rejectedRequestB) {
        jdbc.update("delete from sales.purchase_request where id in (?,?,?,?)",
                requestA, rejectedRequestA, requestB, rejectedRequestB);
        jdbc.update("delete from sales.client_account_membership where client_account_id in (?,?)", accountA, accountB);
        jdbc.update("delete from sales.client_account where id in (?,?)", accountA, accountB);
        jdbc.update("delete from tenant_management.workspace_membership where id in (?,?)", membershipA, membershipB);
        jdbc.update("delete from iam.user_account where id in (?,?)", userA, userB);
        jdbc.update("delete from tenant_management.tenant_business_database_workspace_anchor_task " +
                        "where tenant_id=? and workspace_id=?", tenant, workspace);
        jdbc.update("delete from tenant_management.tenant_business_database_binding where tenant_id=?", tenant);
        jdbc.update("delete from tenant_management.workspace where id=?", workspace);
        jdbc.update("delete from tenant_management.tenant where id=?", tenant);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
