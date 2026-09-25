package com.nexa.api.bootstrap.runtime.database;

import com.nexa.api.bootstrap.runtime.SystemWorkflowActorBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogFamilySkuMappingLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSeedLoader;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogSkuPersistenceBootstrap;
import com.nexa.api.catalogcommercialpolicy.infrastructure.seed.CatalogVariantMappingLoader;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.access.ClientSurface;
import com.nexa.api.tenantaccessgovernance.iam.application.model.AccessPolicy;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.useraccount.UserAccountId;
import com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.JdbcAccessPolicyAdapter;
import com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.JdbcWorkspacePreviewQueryAdapter;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.WorkspacePreviewQueryPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure.persistence.jdbc.JdbcInvitationPersistenceAdapter;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static com.nexa.api.support.PostgresIntegrationSupport.migratorDatabasePassword;
import static com.nexa.api.support.PostgresIntegrationSupport.migratorDatabaseUsername;
import static com.nexa.api.support.PostgresIntegrationSupport.openMigratorConnection;
import static com.nexa.api.support.PostgresIntegrationSupport.openRuntimeConnection;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeDatabasePassword;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeDatabaseUsername;
import static com.nexa.api.support.PostgresIntegrationSupport.runtimeJdbcUrl;

/** Verifies RLS with the real least-privilege runtime login, without role switching. */
@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class RlsRuntimeDatabaseIsolationIT {

    static {
        if (Boolean.getBoolean("nexa.integration.enabled")) {
            Flyway.configure()
                    .dataSource(runtimeJdbcUrl(), migratorDatabaseUsername(), migratorDatabasePassword())
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
        }
    }

    @Test
    void runtimeLoginIsScopedAcrossTenantsAndRlsTablesAndFailsClosedWithoutScope() throws Exception {
        Fixture fixture = insertFixture();
        try {
            try (Connection connection = openRuntimeConnection()) {
                assertRuntimeIdentityAndPrivileges(connection);

                for (ScopedRow row : fixture.rows()) {
                    setSessionScope(connection, row.scope());
                    assertVisibleRows(connection, row, fixture.rows());
                }

                setSessionScope(connection, fixture.rows().get(0).scope());
                ScopedRow foreign = fixture.rows().get(2);
                assertThat(count(connection, "select count(*) from sales.client_account where id = ?", foreign.accountId()))
                        .as("RLS must hide a different tenant even with an explicit id predicate")
                        .isZero();
                assertThat(count(connection, "select count(*) from sales.client_account_address where id = ?", foreign.addressId()))
                        .as("RLS must hide a different tenant in the second protected table")
                        .isZero();

                clearSessionScope(connection);
                assertThat(currentSetting(connection, "app.current_tenant_id")).as("tenant scope after RESET").isIn(null, "");
                assertThat(currentSetting(connection, "app.current_workspace_id")).as("workspace scope after RESET").isIn(null, "");
                assertThat(count(connection, "select count(*) from sales.client_account")).as("missing tenant scope must return no rows").isZero();
                assertThat(count(connection, "select count(*) from sales.client_account_address")).as("missing workspace scope must return no rows").isZero();
            }
        } finally {
            deleteFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    @Test
    void directScopePoliciesFenceCatalogReadsWritesAndRollbackWithRuntimeRole() throws Exception {
        Fixture fixture = insertFixture();
        UUID rolledBackCategory = UUID.randomUUID();
        try {
        try (Connection connection = openRuntimeConnection()) {
            ScopedRow owner = fixture.rows().getFirst();
            ScopedRow foreign = fixture.rows().get(1);
            setSessionScope(connection, owner.scope());
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", owner.categoryId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", foreign.categoryId()))
                    .as("foreign category UUID is hidden despite an explicit ID predicate").isZero();

            assertThat(execute(connection, "update catalog_management.category set name='Updated by scoped runtime' where id=?", owner.categoryId()))
                    .as("USING admits a write to an owned row").isEqualTo(1);
            assertThat(sqlState(connection, "update catalog_management.category set tenant_id=?,workspace_id=? where id=?",
                    foreign.scope().tenantId(), foreign.scope().workspaceId(), owner.categoryId()))
                    .as("WITH CHECK prevents moving an owned row into a different Tenant/Workspace").isEqualTo("42501");
            assertThat(execute(connection, "update catalog_management.category set name='Must remain hidden' where id=?", foreign.categoryId()))
                    .as("USING hides foreign rows from updates").isZero();

            assertThat(sqlState(connection, "insert into warehouse.inventory_transfer(status) values ('IN_TRANSIT')"))
                    .as("restricted runtime cannot create transfers already in transit").isEqualTo("P0001");
            assertThat(sqlState(connection, "insert into warehouse.inventory_transfer(status) values ('RECEIVED')"))
                    .as("restricted runtime cannot create transfers already received").isEqualTo("P0001");

            assertThat(sqlState(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    UUID.randomUUID(), foreign.scope().tenantId(), foreign.scope().workspaceId(), "foreign-" + UUID.randomUUID(), "Foreign category"))
                    .as("WITH CHECK rejects a valid foreign Tenant/Workspace pair").isEqualTo("42501");
            setSessionScope(connection, "", owner.scope().workspaceId().toString());
            assertThat(sqlState(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), "missing-tenant-" + UUID.randomUUID(), "Missing tenant category"))
                    .as("WITH CHECK rejects a missing Tenant scope").isEqualTo("42501");
            setSessionScope(connection, owner.scope().tenantId().toString(), "");
            assertThat(sqlState(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), "missing-workspace-" + UUID.randomUUID(), "Missing workspace category"))
                    .as("WITH CHECK rejects a missing Workspace scope").isEqualTo("42501");
            setSessionScope(connection, foreign.scope().tenantId().toString(), owner.scope().workspaceId().toString());
            assertThat(sqlState(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), "wrong-tenant-" + UUID.randomUUID(), "Wrong tenant category"))
                    .as("WITH CHECK rejects a wrong Tenant scope").isEqualTo("42501");
            setSessionScope(connection, owner.scope().tenantId().toString(), foreign.scope().workspaceId().toString());
            assertThat(sqlState(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), "wrong-workspace-" + UUID.randomUUID(), "Wrong workspace category"))
                    .as("WITH CHECK rejects a wrong Workspace scope").isEqualTo("42501");

            setSessionScope(connection, "", owner.scope().workspaceId().toString());
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", owner.categoryId())).isZero();
            setSessionScope(connection, owner.scope().tenantId().toString(), "");
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", owner.categoryId())).isZero();
            setSessionScope(connection, foreign.scope().tenantId().toString(), owner.scope().workspaceId().toString());
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", owner.categoryId())).isZero();
            setSessionScope(connection, owner.scope().tenantId().toString(), foreign.scope().workspaceId().toString());
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", owner.categoryId())).isZero();
            setSessionScope(connection, owner.scope());
        }

        try (Connection connection = openRuntimeConnection()) {
            connection.setAutoCommit(false);
            setTransactionScope(connection, fixture.rows().getFirst().scope());
            execute(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,created_at,updated_at) values (?,?,?,?,?,current_timestamp,current_timestamp)",
                    rolledBackCategory, fixture.rows().getFirst().scope().tenantId(), fixture.rows().getFirst().scope().workspaceId(),
                    "rollback-" + rolledBackCategory, "Rolled back category");
            connection.rollback();
        }
        try (Connection connection = openRuntimeConnection()) {
            setSessionScope(connection, fixture.rows().getFirst().scope());
            assertThat(count(connection, "select count(*) from catalog_management.category where id=?", rolledBackCategory))
                    .as("a rolled back scoped write is absent after transaction rollback").isZero();
        }
        } finally {
            deleteFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    @Test
    void materialChangeRowsRemainScopedForRestrictedRuntime() throws Exception {
        Fixture fixture = insertFixture();
        ScopedRow owner = fixture.rows().getFirst();
        ScopedRow foreign = fixture.rows().get(1);
        UUID requestId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        try {
            insertPurchaseRequestFixture(owner, requestId);
            Timestamp resolvedAt = Timestamp.from(Instant.now());
            try (Connection connection = openRuntimeConnection()) {
                setSessionScope(connection, owner.scope());
                assertThat(execute(connection, """
                        insert into sales.purchase_request_material_change
                            (id,tenant_id,workspace_id,purchase_request_id,status,proposed_by_membership_id,
                             original_snapshot,proposed_snapshot,proposed_at,request_version)
                        values (?,?,?,?,'PROPOSED',?,?::jsonb,?::jsonb,?,0)
                        """, proposalId, owner.scope().tenantId(), owner.scope().workspaceId(), requestId,
                        owner.membershipId(), "{\"lines\":[{\"quantity\":1}]}",
                        "{\"lines\":[{\"quantity\":2}]}", resolvedAt))
                        .as("the restricted runtime may propose material changes in its active scope")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from sales.purchase_request_material_change where id=?", proposalId))
                        .as("the proposing scope can read its material-change evidence").isEqualTo(1);

                assertThat(sqlState(connection, "update sales.purchase_request_material_change set tenant_id=? where id=?",
                        foreign.scope().tenantId(), proposalId))
                        .as("WITH CHECK rejects moving material-change evidence to another Tenant").isEqualTo("42501");
                assertThat(sqlState(connection, "update sales.purchase_request_material_change set workspace_id=? where id=?",
                        foreign.scope().workspaceId(), proposalId))
                        .as("WITH CHECK rejects moving material-change evidence to another Workspace").isEqualTo("42501");

                setSessionScope(connection, foreign.scope().tenantId().toString(), owner.scope().workspaceId().toString());
                assertThat(count(connection, "select count(*) from sales.purchase_request_material_change where id=?", proposalId))
                        .as("a mismatched Tenant cannot read material-change evidence").isZero();
                assertThat(sqlState(connection, """
                        insert into sales.purchase_request_material_change
                            (id,tenant_id,workspace_id,purchase_request_id,status,proposed_by_membership_id,
                             original_snapshot,proposed_snapshot,proposed_at,request_version)
                        values (?,?,?,?,'PROPOSED',?,?::jsonb,?::jsonb,?,0)
                        """, UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), requestId,
                        owner.membershipId(), "{}", "{}", resolvedAt))
                        .as("WITH CHECK rejects a scoped insert under a different Tenant setting").isEqualTo("42501");

                setSessionScope(connection, owner.scope().tenantId().toString(), foreign.scope().workspaceId().toString());
                assertThat(count(connection, "select count(*) from sales.purchase_request_material_change where id=?", proposalId))
                        .as("a mismatched Workspace cannot read material-change evidence").isZero();
                assertThat(sqlState(connection, """
                        insert into sales.purchase_request_material_change
                            (id,tenant_id,workspace_id,purchase_request_id,status,proposed_by_membership_id,
                             original_snapshot,proposed_snapshot,proposed_at,request_version)
                        values (?,?,?,?,'PROPOSED',?,?::jsonb,?::jsonb,?,0)
                        """, UUID.randomUUID(), owner.scope().tenantId(), owner.scope().workspaceId(), requestId,
                        owner.membershipId(), "{}", "{}", resolvedAt))
                        .as("WITH CHECK rejects a scoped insert under a different Workspace setting").isEqualTo("42501");

                setSessionScope(connection, owner.scope());
                assertThat(execute(connection, """
                        update sales.purchase_request_material_change
                           set status='ACCEPTED', resolved_by_membership_id=?, resolved_at=?,
                               resolved_request_version=request_version+1
                         where id=?
                        """, owner.membershipId(), resolvedAt, proposalId))
                        .as("the restricted runtime may resolve an owned material-change proposal")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from sales.purchase_request_material_change where id=? and status='ACCEPTED'", proposalId))
                        .as("the owned accepted proposal remains visible to its scope").isEqualTo(1);
            }
        } finally {
            try {
                deletePurchaseRequestFixture(owner.scope(), proposalId, requestId);
            } finally {
                try {
                    deleteFixture(fixture);
                } finally {
                    RlsRequestScope.clear();
                }
            }
        }
    }

    @Test
    void canonicalPricePolicyRootsRemainTenantWorkspaceScopedAndReadOnlyForRuntime() throws Exception {
        Fixture fixture = insertFixture();
        ScopedRow owner = fixture.rows().getFirst();
        ScopedRow foreign = fixture.rows().get(1);
        UUID priceListId = UUID.randomUUID();
        UUID customerTermsId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        try {
            try (Connection connection = openMigratorConnection()) {
                connection.setAutoCommit(false);
                setSessionScope(connection, owner.scope());
                execute(connection, "insert into catalog_management.price_list "
                                + "(price_list_id,tenant_id,workspace_id,code,name,currency,status,created_at,updated_at,version) "
                                + "values (?,?,?,?,?,'PEN','ACTIVE',?,?,0)",
                        priceListId, owner.scope().tenantId(), owner.scope().workspaceId(), "RLS-" + priceListId,
                        "RLS price list", now, now);
                execute(connection, "insert into catalog_management.customer_terms "
                                + "(terms_id,tenant_id,workspace_id,customer_account_id,price_list_id,credit_days,currency,valid_from,created_at,version) "
                                + "values (?,?,?,?,?,0,'PEN',?,?,0)",
                        customerTermsId, owner.scope().tenantId(), owner.scope().workspaceId(), owner.accountId(),
                        priceListId, now, now);
                connection.commit();
            }

            try (Connection connection = openRuntimeConnection()) {
                assertRuntimeIdentityAndPrivileges(connection);
                clearSessionScope(connection);
                assertThat(count(connection, "select count(*) from catalog_management.price_list where price_list_id=?", priceListId))
                        .as("missing scope hides Price List rows").isZero();
                assertThat(count(connection, "select count(*) from catalog_management.customer_terms where terms_id=?", customerTermsId))
                        .as("missing scope hides Customer Terms rows").isZero();

                setSessionScope(connection, owner.scope());
                assertThat(count(connection, "select count(*) from catalog_management.price_list where price_list_id=?", priceListId))
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from catalog_management.customer_terms where terms_id=?", customerTermsId))
                        .isEqualTo(1);

                setSessionScope(connection, foreign.scope());
                assertThat(count(connection, "select count(*) from catalog_management.price_list where price_list_id=?", priceListId))
                        .as("a different Workspace cannot read the Price List").isZero();
                assertThat(count(connection, "select count(*) from catalog_management.customer_terms where terms_id=?", customerTermsId))
                        .as("a different Workspace cannot read Customer Terms").isZero();
                assertThat(sqlState(connection, "insert into catalog_management.price_list "
                                + "(price_list_id,tenant_id,workspace_id,code,name,currency,status,created_at,updated_at,version) "
                                + "values (?,?,?,?,?,'PEN','ACTIVE',current_timestamp,current_timestamp,0)",
                        UUID.randomUUID(), foreign.scope().tenantId(), foreign.scope().workspaceId(),
                        "RLS-DENIED-" + UUID.randomUUID(), "Denied price list"))
                        .as("runtime role has no Price List write grant").isEqualTo("42501");
            }
        } finally {
            try (Connection connection = openMigratorConnection()) {
                connection.setAutoCommit(false);
                setSessionScope(connection, owner.scope());
                execute(connection, "delete from catalog_management.customer_terms where terms_id=?", customerTermsId);
                execute(connection, "delete from catalog_management.price_list where price_list_id=?", priceListId);
                connection.commit();
            }
            deleteFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    @Test
    void tenantOnlyConfigurationAndTenantWorkspaceIdentityRowsUseTheirExistingScopePaths() throws Exception {
        int workspaceCountBeforeFixture = countRuntimeRowsWithCrossScopeScan("tenant_management.workspace");
        int tenantCountBeforeFixture = countRuntimeRowsWithCrossScopeScan("tenant_management.tenant");
        Fixture fixture = insertFixture();
        UUID tenantWideRoleId = UUID.randomUUID();
        try (Connection connection = openRuntimeConnection()) {
            ScopedRow owner = fixture.rows().getFirst();
            ScopedRow foreign = fixture.rows().get(1);
            setSessionScope(connection, owner.scope());

            List<String> tenantOnlyTables = List.of(
                    "tenant_management.organization_invitation_idempotency",
                    "tenant_management.organization_settings",
                    "tenant_management.reference_plan_assignment",
                    "tenant_management.regional_settings",
                    "tenant_management.tenant_security_settings",
                    "tenant_management.unit_preferences");
            for (String table : tenantOnlyTables) {
                assertThat(count(connection, "select count(*) from " + table + " where tenant_id=?", owner.scope().tenantId()))
                        .as("matching Tenant reads its row in %s", table).isEqualTo(1);
                assertThat(count(connection, "select count(*) from " + table + " where tenant_id=?", foreign.scope().tenantId()))
                        .as("RLS hides foreign Tenant rows in %s despite an explicit Tenant predicate", table).isZero();
                assertThat(sqlState(connection, "update " + table + " set tenant_id=? where tenant_id=?",
                        foreign.scope().tenantId(), owner.scope().tenantId()))
                        .as("WITH CHECK prevents moving %s into another Tenant", table).isEqualTo("42501");
            }
            assertThat(count(connection, "select count(*) from tenant_management.tenant where id=?", owner.scope().tenantId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from tenant_management.tenant where id=?", foreign.scope().tenantId())).isZero();
            assertThat(count(connection, "select count(*) from tenant_management.workspace where tenant_id=?", owner.scope().tenantId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", foreign.scope().workspaceId())).isZero();

            assertThat(execute(connection, "update tenant_management.organization_settings set display_name='Scoped setting' where tenant_id=?", owner.scope().tenantId()))
                    .as("tenant-only settings accept the matching Tenant without requiring a Workspace setting").isEqualTo(1);
            assertThat(execute(connection, "update tenant_management.organization_settings set display_name='Hidden foreign setting' where tenant_id=?", foreign.scope().tenantId()))
                    .as("USING hides foreign tenant-only settings from writes").isZero();
            assertThat(sqlState(connection, "update tenant_management.organization_settings set tenant_id=? where tenant_id=?",
                    foreign.scope().tenantId(), owner.scope().tenantId()))
                    .as("WITH CHECK prevents moving tenant-owned settings to another Tenant").isEqualTo("42501");
            assertThat(sqlState(connection, "update tenant_management.tenant set id=? where id=?",
                    foreign.scope().tenantId(), owner.scope().tenantId()))
                    .as("Tenant root writes cannot change their own identity to another Tenant").isEqualTo("42501");
            assertThat(sqlState(connection, "insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,? ,?,'ACTIVE',current_timestamp,current_timestamp)",
                    UUID.randomUUID(), foreign.scope().tenantId(), "Wrong tenant workspace", "wrong-" + UUID.randomUUID()))
                    .as("workspace identity writes remain fenced by tenant_id").isEqualTo("42501");

            assertThat(execute(connection, "insert into tenant_management.role_definition(id,tenant_id,workspace_id,code,name,description,role_type,status,created_at,updated_at) values (?,?,NULL,?,?,'Tenant-wide RLS test role','CUSTOM','ACTIVE',current_timestamp,current_timestamp)",
                    tenantWideRoleId, owner.scope().tenantId(), "rls-tenant-role-" + tenantWideRoleId, "Tenant-wide RLS test role"))
                    .as("the existing Tenant-wide custom-role path may persist a NULL workspace_id").isEqualTo(1);
            assertThat(count(connection, "select count(*) from tenant_management.role_definition where id=?", tenantWideRoleId))
                    .as("a tenant-wide custom role is visible inside any Workspace of its Tenant").isEqualTo(1);

            setSessionScope(connection, owner.scope().tenantId().toString(), "");
            for (String table : tenantOnlyTables) {
                assertThat(count(connection, "select count(*) from " + table + " where tenant_id=?", owner.scope().tenantId()))
                        .as("Tenant-wide rows in %s are not coupled to a current Workspace", table).isEqualTo(1);
            }
            assertThat(count(connection, "select count(*) from tenant_management.workspace where tenant_id=?", owner.scope().tenantId()))
                    .as("Tenant administration can see its workspace identities without a current Workspace").isEqualTo(1);
            setSessionScope(connection, foreign.scope().tenantId().toString(), owner.scope().workspaceId().toString());
            for (String table : tenantOnlyTables) {
                assertThat(count(connection, "select count(*) from " + table + " where tenant_id=?", owner.scope().tenantId())).isZero();
            }
            assertThat(count(connection, "select count(*) from tenant_management.role_definition where id=?", tenantWideRoleId))
                    .as("Tenant-wide custom role rows are hidden from another Tenant").isZero();
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", owner.scope().workspaceId())).isZero();

            setSessionScope(connection, "", "");
            setSessionSetting(connection, "app.workspace_lookup_slug", "rls-v1-workspace-" + owner.scope().workspaceId());
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", owner.scope().workspaceId()))
                    .as("identity-first login and public preview can resolve only the requested workspace slug").isEqualTo(1);
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", foreign.scope().workspaceId())).isZero();
            assertThat(count(connection, "select count(*) from tenant_management.tenant where id=?", owner.scope().tenantId()))
                    .as("Tenant root is visible only through the same visible workspace login path").isEqualTo(1);
            setSessionSetting(connection, "app.workspace_lookup_slug", "");

            setSessionSetting(connection, "app.workspace_membership_lookup_id", owner.membershipId().toString());
            setSessionSetting(connection, "app.workspace_membership_lookup_user_id", owner.userId().toString());
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", owner.scope().workspaceId()))
                    .as("session revalidation can resolve only the workspace for its supplied membership").isEqualTo(1);
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", foreign.scope().workspaceId())).isZero();
            setSessionSetting(connection, "app.workspace_membership_lookup_user_id", foreign.userId().toString());
            assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", owner.scope().workspaceId()))
                    .as("a guessed membership ID does not expose another user's workspace").isZero();
            setSessionSetting(connection, "app.workspace_membership_lookup_id", "");
            setSessionSetting(connection, "app.workspace_membership_lookup_user_id", "");

            setSessionSetting(connection, "app.cross_scope_workspace_scan", "true");
            assertThat(count(connection, "select count(*) from tenant_management.workspace"))
                    .isEqualTo(workspaceCountBeforeFixture + fixture.rows().size());
            assertThat(count(connection, "select count(*) from tenant_management.tenant"))
                    .isEqualTo(tenantCountBeforeFixture + fixture.tenantIds().size());
            setSessionSetting(connection, "app.cross_scope_workspace_scan", "");

            setSessionScope(connection, "", owner.scope().workspaceId().toString());
            assertThat(count(connection, "select count(*) from tenant_management.tenant")).isZero();
            assertThat(count(connection, "select count(*) from tenant_management.workspace")).isZero();
            assertThat(count(connection, "select count(*) from tenant_management.organization_settings")).isZero();
        } finally {
            deleteTenantWideRole(tenantWideRoleId, fixture.rows().getFirst().scope());
            deleteFixture(fixture);
        }
    }

    @Test
    void identityFirstAndMembershipResolutionUseTheBoundWorkspacePrecontextPolicies() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            var scopedDataSource = new RlsScopedDataSource(pool);
            JdbcTemplate jdbc = new JdbcTemplate(scopedDataSource);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(scopedDataSource));
            JdbcWorkspacePreviewQueryAdapter preview = new JdbcWorkspacePreviewQueryAdapter(jdbc);
            JdbcAccessPolicyAdapter policies = new JdbcAccessPolicyAdapter(jdbc, request ->
                    EffectiveAuthorization.fixed(java.util.Set.of(MembershipRole.SALES), request.authorizationVersion()));
            ScopedRow owner = fixture.rows().getFirst();
            String workspaceSlug = "rls-v1-workspace-" + owner.scope().workspaceId();

            java.util.Optional<WorkspacePreviewQueryPort.PreviewRecord> previewRecord = transaction.execute(
                    status -> preview.findActiveBySlug(workspaceSlug));
            assertThat(previewRecord)
                    .as("public preview resolves the requested active Workspace under forced RLS")
                    .isPresent();
            assertThat(previewRecord.orElseThrow().slug()).isEqualTo(workspaceSlug);
            java.util.Optional<WorkspacePreviewQueryPort.PreviewRecord> unknownPreview = transaction.execute(
                    status -> preview.findActiveBySlug("unknown-workspace"));
            assertThat(unknownPreview)
                    .as("public preview cannot enumerate other Workspace rows").isEmpty();

            UserAccountId user = new UserAccountId(owner.userId().toString());
            java.util.Optional<AccessPolicy> loginPolicy = transaction.execute(
                    status -> policies.findFor(user, workspaceSlug, ClientSurface.PLATFORM));
            assertThat(loginPolicy)
                    .as("identity-first sign-in resolves only the requested Workspace slug").isPresent();
            java.util.Optional<AccessPolicy> membershipPolicy = transaction.execute(
                    status -> policies.findForMembership(user, owner.membershipId().toString(), ClientSurface.PLATFORM));
            assertThat(membershipPolicy)
                    .as("session revalidation resolves the matching user's exact membership").isPresent();
            java.util.Optional<AccessPolicy> foreignMembershipPolicy = transaction.execute(status -> policies.findForMembership(
                    new UserAccountId(fixture.rows().get(1).userId().toString()), owner.membershipId().toString(), ClientSurface.PLATFORM));
            assertThat(foreignMembershipPolicy)
                    .as("membership revalidation rejects another user's guessed membership UUID").isEmpty();
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    @Test
    void accessContextDiscoveryIsBoundToTrustedIdentityAndClearedAcrossTransactions() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            RlsScopedDataSource scopedDataSource = new RlsScopedDataSource(pool);
            ScopedRow first = fixture.rows().getFirst();
            ScopedRow second = fixture.rows().get(1);
            try (Connection connection = scopedDataSource.getConnection()) {
                int backendPid = count(connection, "select pg_backend_pid()");

                connection.setAutoCommit(false);
                setTransactionSetting(connection, "app.access_context_user_id", first.userId().toString());
                assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", first.scope().workspaceId()))
                        .as("identity discovery sees the authenticated user's Workspace")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", second.scope().workspaceId()))
                        .as("identity discovery hides a different user's Workspace despite an explicit ID predicate")
                        .isZero();
                assertThat(count(connection, "select count(*) from tenant_management.tenant where id=?", first.scope().tenantId()))
                        .as("Tenant root visibility follows the same user's visible Workspace")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from tenant_management.tenant where id=?", second.scope().tenantId()))
                        .isZero();

                connection.commit();
                assertThat(count(connection, "select pg_backend_pid()")).isEqualTo(backendPid);
                assertThat(currentSetting(connection, "app.access_context_user_id"))
                        .as("commit clears the transaction-local discovery identity")
                        .isEmpty();
                assertThat(count(connection, "select count(*) from tenant_management.workspace"))
                        .as("the same connection discovers no Workspace without a trusted identity")
                        .isZero();

                connection.setAutoCommit(false);
                setTransactionSetting(connection, "app.access_context_user_id", second.userId().toString());
                assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", second.scope().workspaceId()))
                        .as("a later transaction may bind the next authenticated identity")
                        .isEqualTo(1);
                assertThat(count(connection, "select count(*) from tenant_management.workspace where id=?", first.scope().workspaceId()))
                        .as("the next identity cannot inherit the preceding user's Workspace")
                        .isZero();

                connection.rollback();
                assertThat(count(connection, "select pg_backend_pid()")).isEqualTo(backendPid);
                assertThat(currentSetting(connection, "app.access_context_user_id"))
                        .as("rollback clears the transaction-local discovery identity")
                        .isEmpty();
                assertThat(count(connection, "select count(*) from tenant_management.workspace"))
                        .as("rollback leaves no prior identity available on the same connection")
                        .isZero();
            }
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    @Test
    void invitationExpiryWorkerTraversesScopesUnderForcedRlsWithBoundedBatches() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            RlsScopedDataSource scopedDataSource = new RlsScopedDataSource(pool);
            JdbcInvitationPersistenceAdapter invitations = new JdbcInvitationPersistenceAdapter(
                    new JdbcTemplate(scopedDataSource), new DataSourceTransactionManager(scopedDataSource));
            assertThat(invitations.expirePending(Instant.now().plusSeconds(1), 2))
                    .as("the cross-scope worker expires no more than its batch limit").isEqualTo(2);
            int expiredAfterFirstBatch = 0;
            for (ScopedRow row : fixture.rows()) {
                try (Connection connection = openRuntimeConnection()) {
                    setSessionScope(connection, row.scope());
                    expiredAfterFirstBatch += count(connection, "select count(*) from tenant_management.organization_invitation where id=? and status='EXPIRED'", row.invitationId());
                }
            }
            assertThat(expiredAfterFirstBatch).isEqualTo(2);
            assertThat(invitations.expirePending(Instant.now().plusSeconds(1), 2))
                    .as("a subsequent bounded pass reaches the remaining workspace").isEqualTo(1);
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    @Test
    void forcedAuditScopePreservesAppendOnlyMutationFence() throws Exception {
        Fixture fixture = insertFixture();
        UUID eventId = UUID.randomUUID();
        try (Connection connection = openRuntimeConnection()) {
            connection.setAutoCommit(false);
            Scope scope = fixture.rows().getFirst().scope();
            setTransactionScope(connection, scope);
            execute(connection, "insert into audit.event(id,tenant_id,workspace_id,event_type,occurred_at) values (?,?,?,'RLS_TEST',current_timestamp)",
                    eventId, scope.tenantId(), scope.workspaceId());

            var updateSavepoint = connection.setSavepoint();
            assertThat(sqlState(connection, "update audit.event set event_type='MUTATED' where id=?", eventId))
                    .as("the existing append-only trigger still rejects runtime updates").isEqualTo("P0001");
            connection.rollback(updateSavepoint);
            var deleteSavepoint = connection.setSavepoint();
            assertThat(sqlState(connection, "delete from audit.event where id=?", eventId))
                    .as("the existing append-only trigger still rejects runtime deletes").isEqualTo("P0001");
            connection.rollback(deleteSavepoint);
            connection.rollback();
        } finally {
            deleteFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    @Test
    void catalogStartupReconcilersAndWorkflowActorBootstrapUseWorkspaceScopes() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            RlsScopedDataSource scopedDataSource = new RlsScopedDataSource(pool);
            JdbcTemplate jdbc = new JdbcTemplate(scopedDataSource);
            TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(scopedDataSource));
            var mapper = JsonMapper.shared();
            CatalogPersistenceBootstrap catalog = new CatalogPersistenceBootstrap(jdbc, new CatalogSeedLoader(mapper));
            CatalogSkuPersistenceBootstrap sku = new CatalogSkuPersistenceBootstrap(jdbc,
                    new CatalogFamilySkuMappingLoader(mapper), new CatalogVariantMappingLoader(mapper));
            SystemWorkflowActorBootstrap actor = new SystemWorkflowActorBootstrap(jdbc);

            transaction.executeWithoutResult(status -> catalog.importDeterministicSeed());
            transaction.executeWithoutResult(status -> sku.reconcile());
            transaction.executeWithoutResult(status -> actor.provision());

            assertThat(RlsRequestScope.current()).as("startup reconciliation leaves no request scope behind").isNull();
            for (ScopedRow row : fixture.rows()) {
                try (Connection connection = openRuntimeConnection()) {
                    setSessionScope(connection, row.scope());
                    assertThat(count(connection, "select count(*) from catalog_management.product where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId()))
                            .as("seed import can write every workspace under forced RLS").isEqualTo(50);
                    assertThat(count(connection, "select count(*) from catalog_management.sellable_sku where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId()))
                            .as("SKU reconciliation can write every workspace under forced RLS").isEqualTo(50);
                    assertThat(count(connection, "select count(*) from tenant_management.membership_role_definition where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId()))
                            .as("workflow actor role provisioning uses the workspace scope").isEqualTo(1);
                }
            }
        } finally {
            RlsRequestScope.clear();
            deleteBootstrapSeed(fixture);
            deleteFixture(fixture);
        }
    }

    @Test
    void pooledRuntimeConnectionClearsScopeBeforeTheSameConnectionIsReused() throws Exception {
        int workspaceCountBeforeFixture = countRuntimeRowsWithCrossScopeScan("tenant_management.workspace");
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            JdbcTemplate scopedJdbc = new JdbcTemplate(new RlsScopedDataSource(pool));

            RlsRequestScope.set(fixture.rows().get(0).scope().tenantId(), fixture.rows().get(0).scope().workspaceId());
            int firstBackendPid = scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class);
            assertThat(scopedJdbc.queryForObject("select current_user", String.class))
                    .as("the pooled connection must authenticate directly as the runtime user")
                    .isEqualTo(runtimeDatabaseUsername());
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("first scoped checkout").isEqualTo(1);
            assertThat(scopedJdbc.queryForObject("select count(*) from catalog_management.category where id=?", Integer.class, fixture.rows().getFirst().categoryId()))
                    .as("direct Catalog scope under the pooled runtime login").isEqualTo(1);

            RlsRequestScope.clear();
            assertThat(scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class))
                    .as("maximumPoolSize=1 must return the same physical connection")
                    .isEqualTo(firstBackendPid);
            assertThat(scopedJdbc.queryForObject("select current_setting('app.current_tenant_id', true)", String.class))
                    .as("scope must be reset before the connection returns to the pool")
                    .isEmpty();
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("a reused connection without a request scope must fail closed")
                    .isEqualTo(0);
            assertThat(scopedJdbc.queryForObject("select count(*) from catalog_management.category where id=?", Integer.class, fixture.rows().getFirst().categoryId()))
                    .as("a reused connection cannot carry scope into direct Catalog tables").isEqualTo(0);

            ScopedRow second = fixture.rows().get(1);
            RlsRequestScope.set(second.scope().tenantId(), second.scope().workspaceId());
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account", Integer.class))
                    .as("the next request must receive only its own workspace")
                    .isEqualTo(1);
            assertThat(scopedJdbc.queryForObject("select count(*) from sales.client_account_address", Integer.class))
                    .as("the next request must receive only its own address rows")
                    .isEqualTo(1);
            assertThat(scopedJdbc.queryForObject("select count(*) from catalog_management.category where id=?", Integer.class, second.categoryId()))
                    .as("the next scope sees only its Catalog row").isEqualTo(1);

            RlsRequestScope.clear();
            RlsRequestScope.enableCrossScopeWorkspaceScan();
            int scanBackendPid = scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class);
            assertThat(scopedJdbc.queryForObject("select count(*) from tenant_management.workspace", Integer.class))
                    .as("named startup and maintenance paths can enumerate workspace scopes")
                    .isEqualTo(workspaceCountBeforeFixture + fixture.rows().size());
            RlsRequestScope.clearCrossScopeWorkspaceScan();
            assertThat(scopedJdbc.queryForObject("select pg_backend_pid()", Integer.class)).isEqualTo(scanBackendPid);
            assertThat(scopedJdbc.queryForObject("select current_setting('app.cross_scope_workspace_scan', true)", String.class))
                    .as("cross-scope worker enumeration is reset before pooled connection reuse").isEmpty();
            assertThat(scopedJdbc.queryForObject("select count(*) from tenant_management.workspace", Integer.class))
                    .as("a reused connection without tenant or worker scope sees no workspace identities").isZero();
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    @Test
    void sameConnectionDoesNotRestorePriorScopeAfterCommitOrRollback() throws Exception {
        Fixture fixture = insertFixture();
        RlsRequestScope.clear();
        try (HikariDataSource pool = runtimePool()) {
            RlsScopedDataSource scopedDataSource = new RlsScopedDataSource(pool);
            ScopedRow first = fixture.rows().getFirst();
            ScopedRow second = fixture.rows().get(1);
            RlsRequestScope.set(first.scope().tenantId(), first.scope().workspaceId());
            RlsRequestScope.enableCrossScopeWorkspaceScan();

            try (Connection connection = scopedDataSource.getConnection()) {
                int backendPid = count(connection, "select pg_backend_pid()");
                connection.setAutoCommit(false);
                assertThat(currentSetting(connection, "app.current_tenant_id"))
                        .as("first transaction uses the first Tenant scope")
                        .isEqualTo(first.scope().tenantId().toString());
                assertThat(count(connection, "select count(*) from catalog_management.category where id=?", first.categoryId()))
                        .as("first scope sees its Catalog row").isEqualTo(1);

                connection.commit();
                assertThat(count(connection, "select pg_backend_pid()")).isEqualTo(backendPid);
                assertThat(currentSetting(connection, "app.current_tenant_id"))
                        .as("commit cleanup clears the session value instead of exposing the transaction's old scope")
                        .isEmpty();
                assertThat(currentSetting(connection, "app.current_workspace_id")).isEmpty();
                assertThat(currentSetting(connection, "app.cross_scope_workspace_scan"))
                        .as("commit cleanup clears the temporary worker scan setting too")
                        .isEmpty();
                assertThat(count(connection, "select count(*) from catalog_management.category where id=?", first.categoryId()))
                        .as("the same connection fails closed after commit until an explicit scope is applied").isZero();

                RlsRequestScope.clearCrossScopeWorkspaceScan();
                RlsRequestScope.set(second.scope().tenantId(), second.scope().workspaceId());
                connection.setAutoCommit(false);
                assertThat(currentSetting(connection, "app.current_tenant_id"))
                        .as("the same physical connection can be assigned the next Tenant scope")
                        .isEqualTo(second.scope().tenantId().toString());
                assertThat(count(connection, "select count(*) from catalog_management.category where id=?", second.categoryId()))
                        .as("second scope sees its Catalog row").isEqualTo(1);

                connection.rollback();
                assertThat(count(connection, "select pg_backend_pid()")).isEqualTo(backendPid);
                assertThat(currentSetting(connection, "app.current_tenant_id"))
                        .as("rollback cleanup clears the second session value instead of restoring it")
                        .isEmpty();
                assertThat(currentSetting(connection, "app.current_workspace_id")).isEmpty();
                assertThat(currentSetting(connection, "app.cross_scope_workspace_scan")).isEmpty();
                assertThat(count(connection, "select count(*) from catalog_management.category where id=?", second.categoryId()))
                        .as("the same connection fails closed after rollback until an explicit scope is applied").isZero();

                RlsRequestScope.set(first.scope().tenantId(), first.scope().workspaceId());
                connection.setAutoCommit(false);
                assertThat(count(connection, "select count(*) from catalog_management.category where id=?", first.categoryId()))
                        .as("a later transaction may explicitly restore the first scope").isEqualTo(1);
            }
        } finally {
            RlsRequestScope.clear();
            deleteFixture(fixture);
        }
    }

    @Test
    void runtimeWorkerScopeCoversWarehouseLogisticsAndRejectsStaleDocumentClaims() throws Exception {
        RuntimeSecurityFixture fixture = insertRuntimeSecurityFixture();
        UUID staleToken = UUID.randomUUID();
        try (Connection connection = openRuntimeConnection()) {
            setSessionScope(connection, fixture.scope());
            assertThat(count(connection, "select count(*) from warehouse.warehouse where id = ?", fixture.warehouseId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from logistics.dispatch_number_counter where tenant_id = ? and workspace_id = ?", fixture.scope().tenantId(), fixture.scope().workspaceId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from business_documents.document_generation_request where id = ?", fixture.generationId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from business_documents.evidence_object where id = ?", fixture.evidenceId())).isEqualTo(1);
            assertThat(count(connection, "select count(*) from notifications.push_subscription where id = ?", fixture.pushSubscriptionId())).isEqualTo(1);

            setSessionScope(connection, new Scope(UUID.randomUUID(), UUID.randomUUID()));
            assertThat(count(connection, "select count(*) from warehouse.warehouse where id = ?", fixture.warehouseId())).isZero();
            assertThat(count(connection, "select count(*) from logistics.dispatch_number_counter where tenant_id = ? and workspace_id = ?", fixture.scope().tenantId(), fixture.scope().workspaceId())).isZero();
            assertThat(count(connection, "select count(*) from business_documents.evidence_object where id = ?", fixture.evidenceId())).isZero();
            assertThat(count(connection, "select count(*) from notifications.push_subscription where id = ?", fixture.pushSubscriptionId())).isZero();

            setSessionScope(connection, fixture.scope());
            assertThat(execute(connection, "update business_documents.document_generation_request set status='COMPLETED',claim_token=null where id=? and tenant_id=? and workspace_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    fixture.generationId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), staleToken)).isZero();
            assertThat(execute(connection, "update business_documents.evidence_object set lifecycle_status='AVAILABLE',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                    fixture.evidenceId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), staleToken)).isZero();
            assertThat(execute(connection, "update business_documents.document_generation_request set status='COMPLETED',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
                    fixture.generationId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), fixture.currentToken())).isEqualTo(1);
            assertThat(execute(connection, "update business_documents.evidence_object set lifecycle_status='DELETED',claim_token=null,lease_until=null where id=? and tenant_id=? and workspace_id=? and lifecycle_status='SCANNING' and claim_token=? and lease_until > current_timestamp",
                    fixture.evidenceId(), fixture.scope().tenantId(), fixture.scope().workspaceId(), fixture.currentToken())).isEqualTo(1);
        } finally {
            deleteRuntimeSecurityFixture(fixture);
            RlsRequestScope.clear();
        }
    }

    private static HikariDataSource runtimePool() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(runtimeJdbcUrl());
        config.setUsername(runtimeDatabaseUsername());
        config.setPassword(runtimeDatabasePassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static RuntimeSecurityFixture insertRuntimeSecurityFixture() throws SQLException {
        UUID tenantId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID generationId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID pushSubscriptionId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        UUID currentToken = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        Scope scope = new Scope(tenantId, workspaceId);
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, scope);
                execute(connection, "insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                        tenantId, "RLS worker tenant", "rls-worker-tenant-" + tenantId, now, now);
                execute(connection, "insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                        workspaceId, tenantId, "RLS worker workspace", "rls-worker-workspace-" + workspaceId, now, now);
                execute(connection, "insert into iam.user_account(id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',?,?,0)",
                        userId, "rls-worker-" + userId + "@example.test", "rls-worker-" + userId + "@example.test", "rls-worker-" + userId, "rls-worker-" + userId, "RLS worker", "es", now, now);
                execute(connection, "insert into tenant_management.workspace_membership(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)",
                        membershipId, workspaceId, userId, now, now);
                execute(connection, "insert into notifications.push_subscription(id,tenant_id,workspace_id,recipient_membership_id,user_id,surface,installation_id,platform,provider_token_hash,status,created_at,updated_at,last_seen_at,version) values (?,?,?,?,?,'PLATFORM',?,'IOS',?,'ENABLED',?,?,?,0)",
                        pushSubscriptionId, tenantId, workspaceId, membershipId, userId, "rls-installation-" + pushSubscriptionId,
                        "a".repeat(64), now, now, now);
                execute(connection, "insert into warehouse.warehouse(id,tenant_id,workspace_id,code,name,status,created_at,updated_at) values (?,?,?,?,'RLS worker warehouse','ACTIVE',?,?)",
                        warehouseId, tenantId, workspaceId, "RLS-" + warehouseId.toString().substring(0, 8), now, now);
                execute(connection, "insert into logistics.dispatch_number_counter(tenant_id,workspace_id,dispatch_year,next_value) values (?,?,?,?)",
                        tenantId, workspaceId, now.toLocalDateTime().getYear(), 1L);
                execute(connection, "insert into business_documents.document_generation_request(id,tenant_id,workspace_id,requested_by_membership_id,document_id,subject_type,subject_id,document_type,format,status,idempotency_key,request_hash,attempt_count,requested_at,processing_started_at,lease_until,claim_token) values (?,?,?,?,null,'SALES_ORDER',?,'ORDER_SUMMARY','PDF','PROCESSING',?,?,1,?,?,?,?)",
                        generationId, tenantId, workspaceId, membershipId, subjectId, "rls-worker-generation-" + generationId, "0".repeat(64), now, now, Timestamp.from(Instant.now().plusSeconds(600)), currentToken);
                execute(connection, "insert into business_documents.evidence_object(id,tenant_id,workspace_id,client_account_id,subject_type,subject_id,object_key,lifecycle_status,declared_content_type,original_filename,scan_attempt_count,next_scan_at,created_at,updated_at,lease_until,claim_token) values (?,?,?,null,'SALES_ORDER',?,null,'SCANNING','application/pdf','worker.pdf',1,?,?,?,?,?)",
                        evidenceId, tenantId, workspaceId, subjectId, now, now, now, Timestamp.from(Instant.now().plusSeconds(600)), currentToken);
                connection.commit();
                return new RuntimeSecurityFixture(scope, warehouseId, generationId, evidenceId, pushSubscriptionId, currentToken,
                        membershipId, userId, tenantId);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteRuntimeSecurityFixture(RuntimeSecurityFixture fixture) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, fixture.scope());
                execute(connection, "delete from notifications.push_subscription where id=?", fixture.pushSubscriptionId());
                execute(connection, "delete from business_documents.document_generation_request where id=?", fixture.generationId());
                execute(connection, "delete from business_documents.evidence_object where id=?", fixture.evidenceId());
                execute(connection, "delete from logistics.dispatch_number_counter where tenant_id=? and workspace_id=?", fixture.scope().tenantId(), fixture.scope().workspaceId());
                execute(connection, "delete from warehouse.warehouse where id=?", fixture.warehouseId());
                execute(connection, "delete from tenant_management.workspace_membership where id=?", fixture.membershipId());
                execute(connection, "delete from iam.user_account where id=?", fixture.userId());
                execute(connection, "delete from tenant_management.workspace where id=?", fixture.scope().workspaceId());
                execute(connection, "delete from tenant_management.tenant where id=?", fixture.tenantId());
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void assertRuntimeIdentityAndPrivileges(Connection connection) throws SQLException {
        assertThat(scalar(connection, "select current_user")).as("current_user").isEqualTo(runtimeDatabaseUsername());
        assertThat(scalar(connection, "select session_user")).as("session_user").isEqualTo(runtimeDatabaseUsername());

        try (PreparedStatement statement = connection.prepareStatement("""
                select rolcanlogin, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole,
                       has_schema_privilege(current_user, 'sales', 'USAGE'),
                       has_schema_privilege(current_user, 'sales', 'CREATE'),
                       has_table_privilege(current_user, 'sales.client_account', 'SELECT'),
                       has_table_privilege(current_user, 'sales.client_account', 'TRUNCATE'),
                       has_database_privilege(current_user, current_database(), 'CREATE'),
                       d.datdba = pg_roles.oid,
                       not exists (select 1 from pg_class owned
                                    join pg_namespace owned_schema on owned_schema.oid = owned.relnamespace
                                   where owned.relowner = pg_roles.oid
                                     and owned.relkind in ('r', 'p', 'v', 'm', 'f')
                                     and owned_schema.nspname not in ('pg_catalog', 'information_schema'))
                from pg_roles
                join pg_database d on d.datname = current_database()
                where pg_roles.rolname = current_user
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).as("runtime role must exist").isTrue();
                assertThat(result.getBoolean(1)).as("runtime role must be able to log in").isTrue();
                assertThat(result.getBoolean(2)).as("runtime role must not be superuser").isFalse();
                assertThat(result.getBoolean(3)).as("runtime role must not bypass RLS").isFalse();
                assertThat(result.getBoolean(4)).as("runtime role must not create databases").isFalse();
                assertThat(result.getBoolean(5)).as("runtime role must not create roles").isFalse();
                assertThat(result.getBoolean(6)).as("runtime role needs only schema usage").isTrue();
                assertThat(result.getBoolean(7)).as("runtime role must not create objects in the schema").isFalse();
                assertThat(result.getBoolean(8)).as("runtime role needs table reads").isTrue();
                assertThat(result.getBoolean(9)).as("runtime role must not truncate tenant data").isFalse();
                assertThat(result.getBoolean(10)).as("runtime role must not have ordinary CREATE authority on the database").isFalse();
                assertThat(result.getBoolean(11)).as("runtime role must not own the application database").isFalse();
                assertThat(result.getBoolean(12)).as("runtime role must not own application objects").isTrue();
                assertThat(result.next()).isFalse();
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                select has_table_privilege(current_user, 'catalog_management.category', 'SELECT'),
                       has_table_privilege(current_user, 'catalog_management.category', 'INSERT'),
                       has_table_privilege(current_user, 'catalog_management.category', 'UPDATE'),
                       has_table_privilege(current_user, 'catalog_management.category', 'DELETE'),
                       has_table_privilege(current_user, 'audit.event', 'INSERT'),
                       has_table_privilege(current_user, 'audit.event', 'UPDATE'),
                       has_table_privilege(current_user, 'audit.event', 'DELETE'),
                       has_table_privilege(current_user, 'audit.event', 'TRUNCATE')
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                for (int column = 1; column <= 7; column++) {
                    assertThat(result.getBoolean(column)).as("V102 preserves the existing runtime DML matrix for Catalog and audit").isTrue();
                }
                assertThat(result.getBoolean(8)).as("runtime must not truncate append-only audit history").isFalse();
                assertThat(result.next()).isFalse();
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                select has_table_privilege(current_user, 'sales.purchase_request_material_change', 'SELECT'),
                       has_table_privilege(current_user, 'sales.purchase_request_material_change', 'INSERT'),
                       has_table_privilege(current_user, 'sales.purchase_request_material_change', 'UPDATE'),
                       has_table_privilege(current_user, 'sales.purchase_request_material_change', 'DELETE')
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).as("restricted runtime may read material-change evidence").isTrue();
                assertThat(result.getBoolean(2)).as("restricted runtime may propose material changes").isTrue();
                assertThat(result.getBoolean(3)).as("restricted runtime may resolve material changes").isTrue();
                assertThat(result.getBoolean(4)).as("restricted runtime cannot delete material-change evidence").isFalse();
            }
        }

        List<String> forceRlsTables = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select n.nspname || '.' || c.relname
                  from pg_class c
                  join pg_namespace n on n.oid = c.relnamespace
                 where c.relkind = 'r'
                   and c.relrowsecurity
                   and c.relforcerowsecurity
                 order by n.nspname, c.relname
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) forceRlsTables.add(result.getString(1));
            }
        }
        assertThat(forceRlsTables)
                .as("RLS must be enabled and forced for every table used by this isolation proof")
                .containsExactlyInAnyOrder("business_documents.business_document", "business_documents.evidence_object", "business_documents.object_storage_object",
                        "notifications.inbox_item", "notifications.push_subscription", "notifications.push_subscription_command_idempotency", "notifications.push_delivery_attempt", "notifications.push_delivery_claim",
                        "payments.credit_account", "payments.credit_reservation", "payments.payment", "payments.payment_attempt", "payments.payment_event", "payments.payment_reconciliation_case", "payments.reconciliation_refund_idempotency", "payments.receivable", "payments.receivable_allocation",
                        "warehouse.warehouse", "warehouse.storage_zone", "warehouse.inventory_lot", "warehouse.stock_movement", "warehouse.inventory_event", "warehouse.inventory_reservation", "warehouse.command_idempotency", "warehouse.warehouse_service_configuration", "warehouse.selection_snapshot", "warehouse.inventory_lot_disposition", "warehouse.inventory_temperature_evaluation", "warehouse.physical_allocation", "warehouse.physical_allocation_line", "warehouse.physical_allocation_event", "warehouse.physical_allocation_command_idempotency",
                        "logistics.dispatch_number_counter", "logistics.dispatch_order", "logistics.dispatch_event", "logistics.command_idempotency", "logistics.proof_of_delivery", "logistics.temperature_reading", "logistics.delivery_incident", "logistics.operational_handoff_note", "logistics.delivery_attempt", "logistics.delivery_attempt_line", "logistics.continuation_delivery", "logistics.continuation_delivery_line", "logistics.fulfillment", "logistics.fulfillment_line", "logistics.fulfillment_command_idempotency", "logistics.fulfillment_event", "logistics.picking_result", "logistics.picking_result_line", "logistics.picking_discrepancy", "logistics.picking_discrepancy_resolution", "logistics.delivery", "logistics.delivery_command_idempotency", "logistics.delivery_assignment", "logistics.delivery_quantity_outcome", "logistics.delivery_event", "logistics.temperature_evidence", "logistics.temperature_excursion", "logistics.proof_of_delivery_addendum", "logistics.delivery_handoff_token", "logistics.buyer_receipt_fact",
                        "warehouse.safety_stock_policy", "warehouse.inventory_transfer", "warehouse.inventory_transfer_history", "warehouse.inventory_backing", "warehouse.inventory_backing_line", "warehouse.inventory_backing_position", "payments.financial_adjustment", "payments.financial_ledger_entry", "payments.refund_credit_obligation", "payments.receivable_application",
                        "sales.client_account", "sales.client_account_address", "sales.client_account_membership", "sales.commercial_commitment", "sales.commercial_commitment_line", "sales.manual_sales_order_draft", "sales.manual_sales_order_draft_idempotency", "sales.manual_sales_order_draft_line", "sales.purchase_request", "sales.purchase_request_event", "sales.purchase_request_material_change", "sales.idempotency_record", "sales.idempotency_response", "sales.purchase_request_draft", "sales.purchase_request_draft_destination", "sales.purchase_request_draft_idempotency", "sales.purchase_request_draft_line", "sales.purchase_request_draft_route", "sales.purchase_request_draft_warehouse_selection", "sales.sales_order", "sales.sales_order_event",
                        "audit.event",
                        "catalog_management.brand", "catalog_management.category", "catalog_management.command_idempotency", "catalog_management.customer_terms", "catalog_management.price_list", "catalog_management.price_list_item", "catalog_management.product", "catalog_management.product_asset_reference", "catalog_management.product_family", "catalog_management.product_presentation", "catalog_management.product_price", "catalog_management.product_variant", "catalog_management.product_visibility", "catalog_management.promotion", "catalog_management.promotion_category", "catalog_management.promotion_client_account", "catalog_management.promotion_product", "catalog_management.promotion_rule", "catalog_management.promotion_sku", "catalog_management.seed_import_history", "catalog_management.sellable_sku", "catalog_management.sku_price",
                        "sales.manual_order_idempotency", "sales.sales_order_sequence",
                        "tenant_management.custom_field_definition", "tenant_management.membership_admin_event", "tenant_management.membership_authorization_state", "tenant_management.membership_role_assignment", "tenant_management.membership_role_definition", "tenant_management.organization_invitation", "tenant_management.organization_registration", "tenant_management.role_definition", "tenant_management.workspace_creation_idempotency",
                        "tenant_management.organization_invitation_idempotency", "tenant_management.organization_settings", "tenant_management.reference_plan_assignment", "tenant_management.regional_settings", "tenant_management.tenant_security_settings", "tenant_management.unit_preferences", "tenant_management.tenant", "tenant_management.workspace");
    }

    private static void assertVisibleRows(Connection connection, ScopedRow expected, List<ScopedRow> allRows) throws SQLException {
        assertThat(count(connection, "select count(*) from sales.client_account"))
                .as("client accounts visible for tenant %s/workspace %s", expected.scope().tenantId(), expected.scope().workspaceId())
                .isEqualTo(1);
        assertThat(count(connection, "select count(*) from sales.client_account_address"))
                .as("addresses visible for tenant %s/workspace %s", expected.scope().tenantId(), expected.scope().workspaceId())
                .isEqualTo(1);
        assertThat(count(connection, "select count(*) from sales.client_account where tenant_id = ? and workspace_id = ?",
                expected.scope().tenantId(), expected.scope().workspaceId())).isEqualTo(1);
        for (ScopedRow foreign : allRows) {
            if (foreign == expected) continue;
            assertThat(count(connection, "select count(*) from sales.client_account where id = ?", foreign.accountId()))
                    .as("foreign account must be invisible").isZero();
            assertThat(count(connection, "select count(*) from sales.client_account_address where id = ?", foreign.addressId()))
                    .as("foreign address must be invisible").isZero();
        }
    }

    private static Fixture insertFixture() throws SQLException {
        UUID tenantOne = UUID.randomUUID();
        UUID tenantTwo = UUID.randomUUID();
        UUID tenantThree = UUID.randomUUID();
        List<ScopedRow> rows = new ArrayList<>();
        rows.add(insertRow(tenantOne, UUID.randomUUID(), "one", true));
        rows.add(insertRow(tenantTwo, UUID.randomUUID(), "two", true));
        rows.add(insertRow(tenantThree, UUID.randomUUID(), "three", true));
        return new Fixture(rows, List.of(tenantOne, tenantTwo, tenantThree));
    }

    private static void insertPurchaseRequestFixture(ScopedRow row, UUID requestId) throws SQLException {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp expiresAt = Timestamp.from(Instant.now().plusSeconds(86_400));
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, row.scope());
                execute(connection, """
                        insert into sales.purchase_request
                            (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,
                             created_at,updated_at,submitted_at,expires_at)
                        values (?,?,?,?,?,?,'SUBMITTED','NORMAL',?,?,?,?)
                        """, requestId, row.scope().tenantId(), row.scope().workspaceId(), row.accountId(),
                        row.membershipId(), "RLS-MATERIAL-" + requestId.toString().substring(0, 8), now, now, now, expiresAt);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deletePurchaseRequestFixture(Scope scope, UUID proposalId, UUID requestId) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, scope);
                execute(connection, "delete from sales.purchase_request_material_change where id=?", proposalId);
                execute(connection, "delete from sales.purchase_request where id=?", requestId);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static ScopedRow insertRow(UUID tenantId, UUID workspaceId, String label, boolean createTenant) throws SQLException {
        UUID accountId = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UUID invitationId = UUID.randomUUID();
        String invitationTokenHash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, new Scope(tenantId, workspaceId));
                if (createTenant) {
                    execute(connection, "insert into tenant_management.tenant(id,name,slug,status,created_at,updated_at) values (?,?,?,'ACTIVE',?,?)",
                            tenantId, "RLS V1 tenant " + label, "rls-v1-tenant-" + tenantId, now, now);
                }
                execute(connection, "insert into tenant_management.workspace(id,tenant_id,name,slug,status,created_at,updated_at) values (?,?,?,?,'ACTIVE',?,?)",
                        workspaceId, tenantId, "RLS V1 workspace " + label, "rls-v1-workspace-" + workspaceId, now, now);
                execute(connection, "insert into iam.user_account(id,email,normalized_email,username,normalized_username,display_name,preferred_language,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,'ACTIVE',?,?,0)",
                        userId, "rls-v1-" + userId + "@example.test", "rls-v1-" + userId + "@example.test", "rls-v1-" + userId, "rls-v1-" + userId, "RLS V1 Test", "es", now, now);
                execute(connection, "insert into tenant_management.workspace_membership(id,workspace_id,user_id,membership_type,status,created_at,updated_at,version) values (?,?,?,'INTERNAL','ACTIVE',?,?,0)",
                        membershipId, workspaceId, userId, now, now);
                execute(connection, "insert into catalog_management.category(id,tenant_id,workspace_id,slug,name,status,version,created_at,updated_at) values (?,?,?,?,'RLS V1 category','ACTIVE',0,?,?)",
                        categoryId, tenantId, workspaceId, "rls-v1-" + label, now, now);
                execute(connection, "insert into sales.client_account(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        accountId, tenantId, workspaceId, "RLSV1-" + label + "-" + accountId.toString().substring(0, 8),
                        "RLS V1 business " + label, "RLS V1 commercial " + label, "PE", "RUC", "RLSV1" + accountId.toString().replace("-", "").substring(0, 10),
                        "STANDARD", "RLS V1 Test", "rls-v1-" + label + "@example.test", "+51000000000", "STANDARD", "CREDIT", "ACTIVE", now, now);
                execute(connection, "insert into sales.client_account_address(id,tenant_id,workspace_id,client_account_id,label,recipient_name,address_line,source,default_address,status,created_at,updated_at) values (?,?,?,?,?,?,?,'MANUAL',true,'ACTIVE',?,?)",
                        addressId, tenantId, workspaceId, accountId, "RLS V1 address " + label, "RLS V1 recipient", "RLS V1 address line", now, now);
                execute(connection, "insert into tenant_management.organization_invitation(id,tenant_id,workspace_id,email,normalized_email,display_name,token_hash,status,expires_at,created_by_membership_id,version,created_at,updated_at) values (?,?,?,?,?,?,?,'PENDING',?,?,0,?,?)",
                        invitationId, tenantId, workspaceId, "invite-" + userId + "@example.test", "invite-" + userId + "@example.test", "RLS V1 Invite", invitationTokenHash,
                        Timestamp.from(Instant.now().minusSeconds(60)), membershipId, now, now);
                execute(connection, "insert into tenant_management.organization_settings(tenant_id,legal_name,display_name,operation_category,version,updated_at) values (?,? ,?,'B2B_COLD_CHAIN_DISTRIBUTOR',0,?)",
                        tenantId, "RLS V1 legal " + label, "RLS V1 organization " + label, now);
                execute(connection, "insert into tenant_management.regional_settings(tenant_id,updated_at) values (?,?)", tenantId, now);
                execute(connection, "insert into tenant_management.unit_preferences(tenant_id,updated_at) values (?,?)", tenantId, now);
                execute(connection, "insert into tenant_management.tenant_security_settings(tenant_id,updated_at) values (?,?)", tenantId, now);
                execute(connection, "insert into tenant_management.reference_plan_assignment(tenant_id,updated_at) values (?,?)", tenantId, now);
                execute(connection, "insert into tenant_management.organization_invitation_idempotency(tenant_id,idempotency_key,request_hash,invitation_id,created_at) values (?,?,'0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',?,?)",
                        tenantId, "rls-v102-" + label, invitationId, now);
                connection.commit();
                return new ScopedRow(new Scope(tenantId, workspaceId), accountId, addressId, categoryId, invitationId, membershipId, userId);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteFixture(Fixture fixture) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                for (ScopedRow row : fixture.rows()) {
                    setSessionScope(connection, row.scope());
                    execute(connection, "delete from tenant_management.organization_invitation_idempotency where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from tenant_management.organization_invitation where id=?", row.invitationId());
                    execute(connection, "delete from tenant_management.organization_settings where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from tenant_management.regional_settings where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from tenant_management.unit_preferences where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from tenant_management.tenant_security_settings where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from tenant_management.reference_plan_assignment where tenant_id=?", row.scope().tenantId());
                    execute(connection, "delete from sales.client_account_address where id = ?", row.addressId());
                    execute(connection, "delete from sales.client_account where id = ?", row.accountId());
                    execute(connection, "delete from catalog_management.category where id=?", row.categoryId());
                    execute(connection, "delete from tenant_management.workspace_membership where id=?", row.membershipId());
                    execute(connection, "delete from iam.user_account where id=?", row.userId());
                    execute(connection, "delete from tenant_management.workspace where id = ?", row.scope().workspaceId());
                }
                for (UUID tenantId : fixture.tenantIds()) {
                    setSessionScope(connection, tenantId.toString(), "");
                    execute(connection, "delete from tenant_management.tenant where id = ?", tenantId);
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteTenantWideRole(UUID roleId, Scope scope) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                setSessionScope(connection, scope);
                execute(connection, "delete from tenant_management.role_definition where id=?", roleId);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteBootstrapSeed(Fixture fixture) throws SQLException {
        try (Connection connection = openMigratorConnection()) {
            connection.setAutoCommit(false);
            try {
                for (ScopedRow row : fixture.rows()) {
                    setSessionScope(connection, row.scope());
                    execute(connection, "delete from tenant_management.membership_role_definition where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from tenant_management.membership_authorization_state where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from tenant_management.workspace_membership where workspace_id=? and membership_type='SYSTEM_WORKFLOW'", row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.promotion_sku where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.sku_price where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.sellable_sku where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_variant where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_family where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_presentation where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_visibility where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_asset_reference where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product_price where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.product where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.seed_import_history where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.category where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                    execute(connection, "delete from catalog_management.brand where tenant_id=? and workspace_id=?", row.scope().tenantId(), row.scope().workspaceId());
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void setSessionScope(Connection connection, Scope scope) throws SQLException {
        setSessionScope(connection, scope.tenantId().toString(), scope.workspaceId().toString());
    }

    private static void setSessionScope(Connection connection, String tenantId, String workspaceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('app.current_tenant_id', ?, false), set_config('app.current_workspace_id', ?, false)")) {
            statement.setString(1, tenantId);
            statement.setString(2, workspaceId);
            statement.execute();
        }
    }

    private static void setSessionSetting(Connection connection, String setting, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, false)")) {
            statement.setString(1, setting);
            statement.setString(2, value);
            statement.execute();
        }
    }

    private static void setTransactionScope(Connection connection, Scope scope) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config('app.current_tenant_id', ?, true), set_config('app.current_workspace_id', ?, true)")) {
            statement.setString(1, scope.tenantId().toString());
            statement.setString(2, scope.workspaceId().toString());
            statement.execute();
        }
    }

    private static void setTransactionSetting(Connection connection, String setting, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select set_config(?, ?, true)")) {
            statement.setString(1, setting);
            statement.setString(2, value);
            statement.execute();
        }
    }

    private static void clearSessionScope(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("reset app.current_tenant_id");
            statement.execute("reset app.current_workspace_id");
        }
    }

    private static String currentSetting(Connection connection, String setting) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select current_setting(?, true)")) {
            statement.setString(1, setting);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getString(1);
            }
        }
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static int count(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int countRuntimeRowsWithCrossScopeScan(String tableName) throws SQLException {
        try (Connection connection = openRuntimeConnection()) {
            setSessionSetting(connection, "app.cross_scope_workspace_scan", "true");
            return count(connection, "select count(*) from " + tableName);
        }
    }

    private static int execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            return statement.executeUpdate();
        }
    }

    private static String sqlState(Connection connection, String sql, Object... arguments) throws SQLException {
        try {
            execute(connection, sql, arguments);
            return null;
        } catch (SQLException exception) {
            return exception.getSQLState();
        }
    }

    private record Scope(UUID tenantId, UUID workspaceId) { }
    private record ScopedRow(Scope scope, UUID accountId, UUID addressId, UUID categoryId, UUID invitationId,
                             UUID membershipId, UUID userId) { }
    private record Fixture(List<ScopedRow> rows, List<UUID> tenantIds) { }
    private record RuntimeSecurityFixture(Scope scope, UUID warehouseId, UUID generationId, UUID evidenceId,
                                          UUID pushSubscriptionId,
                                          UUID currentToken, UUID membershipId, UUID userId, UUID tenantId) { }

}
