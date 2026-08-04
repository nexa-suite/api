package com.nexa.api.shared.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the PostgreSQL policy itself, independently of application WHERE clauses. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class RlsDatabaseIsolationIT extends PostgresIntegrationSupport {
    private static final String PROBE_ROLE = "nexa_rls_probe";

    @Test
    @Transactional
    void nonOwnerRoleCannotReadAnotherTenantWhenConnectionScopeIsSet() {
        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        UUID workspaceOne = UUID.randomUUID();
        UUID workspaceTwo = UUID.randomUUID();
        UUID accountOne = UUID.randomUUID();
        UUID accountTwo = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());

        jdbc.update("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                tenantOne, "RLS tenant one", "rls-" + tenantOne, now, now);
        jdbc.update("insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                tenantTwo, "RLS tenant two", "rls-" + tenantTwo, now, now);
        jdbc.update("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                workspaceOne, tenantOne, "RLS workspace one", "one-" + workspaceOne, now, now);
        jdbc.update("insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                workspaceTwo, tenantTwo, "RLS workspace two", "two-" + workspaceTwo, now, now);
        insertAccount(accountOne, tenantOne, workspaceOne, "RLS-ONE");
        insertAccount(accountTwo, tenantTwo, workspaceTwo, "RLS-TWO");

        jdbc.execute("do $$ begin if not exists (select 1 from pg_roles where rolname='" + PROBE_ROLE + "') then create role " + PROBE_ROLE + " nologin; end if; end $$");
        jdbc.execute("grant usage on schema sales to " + PROBE_ROLE);
        jdbc.execute("grant select on sales.client_account to " + PROBE_ROLE);
        jdbc.execute("grant " + PROBE_ROLE + " to nexa");
        jdbc.execute("set local role " + PROBE_ROLE);

        setScope(tenantOne, workspaceOne);
        assertThat(jdbc.queryForObject("select count(*) from sales.client_account", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sales.client_account where tenant_id=?", Integer.class, tenantTwo)).isZero();

        setScope(tenantTwo, workspaceTwo);
        assertThat(jdbc.queryForObject("select count(*) from sales.client_account", Integer.class)).isEqualTo(1);
    }

    private void insertAccount(UUID id, UUID tenant, UUID workspace, String code) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into sales.client_account(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, workspace, code, code + " business", code + " commercial", "PE", "RUC", code + "123", "STANDARD", "RLS Test", "rls@example.test", "+51000000000", "STANDARD", "CREDIT", "ACTIVE", now, now);
    }

    private void setScope(UUID tenant, UUID workspace) {
        jdbc.query("select set_config('app.current_tenant_id', ?, true), set_config('app.current_workspace_id', ?, true)",
                (rs, row) -> null, tenant.toString(), workspace.toString());
    }
}
