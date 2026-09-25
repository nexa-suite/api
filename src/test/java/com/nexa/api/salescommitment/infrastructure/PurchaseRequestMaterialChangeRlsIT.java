package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class PurchaseRequestMaterialChangeRlsIT extends PostgresIntegrationSupport {
    @Test
    void materialChangeTableIsScopedAndRuntimePrivilegesExcludeDelete() throws Exception {
        assertThat(jdbc.queryForObject("""
                select c.relrowsecurity and c.relforcerowsecurity
                from pg_class c join pg_namespace n on n.oid=c.relnamespace
                where n.nspname='sales' and c.relname='purchase_request_material_change'
                """, Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege('nexa_runtime','sales.purchase_request_material_change','SELECT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege('nexa_runtime','sales.purchase_request_material_change','INSERT')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege('nexa_runtime','sales.purchase_request_material_change','UPDATE')", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("select has_table_privilege('nexa_runtime','sales.purchase_request_material_change','DELETE')", Boolean.class)).isFalse();

        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USERNAME, RUNTIME_PASSWORD);
             var statement = connection.prepareStatement("select set_config('app.current_tenant_id', ?, false), set_config('app.current_workspace_id', ?, false)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, UUID.randomUUID().toString());
            statement.execute();
            try (var query = connection.createStatement(); var result = query.executeQuery("select count(*) from sales.purchase_request_material_change")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }

        String requestStatusConstraint = jdbc.queryForObject("""
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid='sales.purchase_request'::regclass and conname='ck_purchase_request_status_v3'
        """, String.class);
        assertThat(requestStatusConstraint).contains("CANCELLED");
        String buyerIdentityConstraint = jdbc.queryForObject("""
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid='sales.sales_order'::regclass and conname='ck_sales_order_buyer_required_unless_manual'
                """, String.class);
        assertThat(buyerIdentityConstraint).contains("MANUAL", "buyer_membership_id IS NOT NULL");
        String proposalStatusConstraint = jdbc.queryForObject("""
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conrelid='sales.purchase_request_material_change'::regclass and conname='ck_pr_material_change_status'
                """, String.class);
        assertThat(proposalStatusConstraint).contains("PROPOSED", "ACCEPTED", "REJECTED", "EXPIRED");
    }
}
