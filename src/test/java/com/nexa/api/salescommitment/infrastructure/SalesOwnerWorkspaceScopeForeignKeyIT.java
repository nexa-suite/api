package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOwnerWorkspaceScopeForeignKeyIT extends PostgresIntegrationSupport {

    @Test
    void centralSalesOwnerReferenceCannotCrossTenantWorkspace() {
        UUID tenantA = UUID.fromString(tenantId());
        UUID workspaceA = UUID.fromString(workspaceId());
        UUID tenantB = UUID.randomUUID();
        UUID workspaceB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID sellerB = UUID.randomUUID();
        UUID account = UUID.randomUUID();
        UUID sellerA = UUID.fromString(membershipId(SALES_EMAIL));
        String suffix = account.toString().replace("-", "").substring(0, 12).toUpperCase();
        String email = "seller-fk-" + suffix.toLowerCase() + "@example.test";
        String username = "seller_fk_" + suffix.toLowerCase();

        try {
            jdbc.update("insert into tenant_management.tenant (id,name,slug,status,created_at,updated_at,version) " +
                            "values (?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                    tenantB, "Sales owner scope fixture", "sales-fk-tenant-" + tenantB.toString().replace("-", ""));
            jdbc.update("insert into tenant_management.workspace (id,tenant_id,name,slug,status,created_at,updated_at,version) " +
                            "values (?,?,?,?,'ACTIVE',current_timestamp,current_timestamp,0)",
                    workspaceB, tenantB, "Sales owner scope fixture", "sales-fk-" + suffix.toLowerCase());
            jdbc.update("insert into iam.user_account (id,email,normalized_email,username,normalized_username,display_name," +
                            "preferred_language,status,created_at,updated_at,version) " +
                            "values (?,?,?,?,?,?,'en','ACTIVE',current_timestamp,current_timestamp,0)",
                    userB, email, email, username, username, "Sales FK fixture");
            jdbc.update("insert into tenant_management.workspace_membership " +
                            "(id,tenant_id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) " +
                            "values (?,?,?,?,'INTERNAL','ACTIVE',current_timestamp,current_timestamp,0)",
                    sellerB, tenantB, workspaceB, userB);
            jdbc.update("insert into sales.client_account " +
                            "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code," +
                            "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone," +
                            "delivery_profile,payment_condition,status,sales_owner_membership_id,created_at,updated_at,version) " +
                            "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    account, tenantA, workspaceA, "SO-FK-" + suffix, "Owner FK Fixture", "Owner FK Fixture",
                    "PE", "RUC", "20" + String.format(java.util.Locale.ROOT, "%09d",
                            Math.floorMod(account.getLeastSignificantBits(), 1_000_000_000L)),
                    "UNASSIGNED", "Fixture", "owner-fk-" + suffix.toLowerCase() + "@example.test", "+51999999999",
                    "", "CASH", "ACTIVE", sellerA, java.sql.Timestamp.from(java.time.Instant.now()),
                    java.sql.Timestamp.from(java.time.Instant.now()));

            DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update("update sales.client_account set sales_owner_membership_id=? " +
                                    "where tenant_id=? and workspace_id=? and id=?",
                            sellerB, tenantA, workspaceA, account));
            SQLException sqlException = findSqlException(failure);
            assertNotNull(sqlException, "PostgreSQL must report the failed FK");
            assertThat(sqlException.getSQLState()).isEqualTo("23503");
            assertThat(sqlException.getMessage()).contains("fk_client_account_sales_owner_scope_membership");
        } finally {
            jdbc.update("delete from sales.client_account where id=?", account);
            jdbc.update("delete from tenant_management.workspace_membership where id=?", sellerB);
            jdbc.update("delete from iam.user_account where id=?", userB);
            jdbc.update("delete from tenant_management.workspace where id=?", workspaceB);
            jdbc.update("delete from tenant_management.tenant where id=?", tenantB);
        }
    }

    private static SQLException findSqlException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException;
        }
        return null;
    }
}
