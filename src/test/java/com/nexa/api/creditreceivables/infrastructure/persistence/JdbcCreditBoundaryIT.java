package com.nexa.api.creditreceivables.infrastructure.persistence;

import com.nexa.api.bootstrap.runtime.boundaries.TenantCreditEligibilitySourceFactory;
import com.nexa.api.customerbuyerrelationships.infrastructure.persistence.ClientAccountPersistenceAdapter;
import com.nexa.api.support.PostgresIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class JdbcCreditBoundaryIT extends PostgresIntegrationSupport {

    @Test
    void legacyLimitWithoutModernAccountCannotBootstrapCreditDuringReservation() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.randomUUID();
        UUID commitment = UUID.randomUUID();
        String suffix = account.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        String taxValue = "20" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(account.getLeastSignificantBits(), 1_000_000_000L));
        JdbcTemplate database = tenantJdbc();
        Timestamp now = Timestamp.from(Instant.now());

        try {
            database.update("insert into sales.client_account "
                            + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                            + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                            + "delivery_profile,payment_condition,status,credit_limit,current_commercial_exposure,"
                            + "available_credit,credit_currency,created_at,updated_at,version) "
                            + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                    account, tenant, workspace, "CR-" + suffix, "Synthetic Credit Buyer " + suffix,
                    "Synthetic Credit Buyer " + suffix, "PE", "RUC", taxValue, "SYNTHETIC", "Fixture Contact",
                    "credit-fk-" + suffix.toLowerCase(java.util.Locale.ROOT) + "@example.test", "+51999999999",
                    "Synthetic fixture", "CREDIT_30", "ACTIVE", new BigDecimal("1000.00"), BigDecimal.ZERO,
                    new BigDecimal("1000.00"), "PEN", now, now);
            database.update("insert into sales.commercial_commitment "
                            + "(id,tenant_id,workspace_id,purchase_request_id,sales_order_id,client_account_id,"
                            + "status,created_at,updated_at,version,origin_type) "
                            + "values (?,?,?,null,null,?,'ACTIVE',?,?,0,'DIRECT_ORDER')",
                    commitment, tenant, workspace, account, now, now);

            assertThat(database.queryForObject("select count(*) from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                    Integer.class, tenant, workspace, account)).isZero();
            JdbcCreditBoundary boundary = creditBoundary(database);
            assertThatThrownBy(() -> inTenantTransaction(database, () -> boundary.reserveForCommitment(tenant,
                    workspace, account, commitment, null, null, new BigDecimal("10.00"), "PEN", Instant.now())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Client credit account is not configured");
            assertThat(database.queryForObject("select count(*) from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and client_account_id=? and currency='PEN'",
                    Integer.class, tenant, workspace, account)).isZero();
            assertThat(database.queryForObject("select count(*) from payments.credit_reservation "
                            + "where tenant_id=? and workspace_id=? and commercial_commitment_id=?",
                    Integer.class, tenant, workspace, commitment)).isZero();
        } finally {
            database.update("delete from payments.credit_reservation where tenant_id=? and workspace_id=? "
                    + "and commercial_commitment_id=?", tenant, workspace, commitment);
            database.update("delete from payments.credit_account where tenant_id=? and workspace_id=? "
                    + "and client_account_id=?", tenant, workspace, account);
            database.update("delete from sales.commercial_commitment where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, commitment);
            database.update("delete from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, account);
        }
    }

    @Test
    void existingReservationCanBeReleasedAfterCreditAccountIsSuspended() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.randomUUID();
        UUID commitment = UUID.randomUUID();
        UUID creditAccount = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID secondCommitment = UUID.randomUUID();
        String suffix = account.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        String taxValue = "20" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(account.getLeastSignificantBits(), 1_000_000_000L));
        JdbcTemplate database = tenantJdbc();
        Timestamp now = Timestamp.from(Instant.now());
        BigDecimal amount = new BigDecimal("10.00");

        try {
            insertClientAccount(database, tenant, workspace, account, suffix, taxValue, now);
            insertCommitment(database, tenant, workspace, account, commitment, now);
            insertCommitment(database, tenant, workspace, account, secondCommitment, now);
            database.update("insert into payments.credit_account "
                            + "(id,tenant_id,workspace_id,client_account_id,currency,credit_limit,reserved_exposure,"
                            + "status,created_at,updated_at) values (?,?,?,?,'PEN',?,?,'SUSPENDED',?,?)",
                    creditAccount, tenant, workspace, account, new BigDecimal("1000.00"), amount, now, now);
            database.update("insert into payments.credit_reservation "
                            + "(id,tenant_id,workspace_id,credit_account_id,commercial_commitment_id,amount,status,"
                            + "idempotency_key,created_at) values (?,?,?,?,?,?,'RESERVED',?,?)",
                    reservation, tenant, workspace, creditAccount, commitment, amount,
                    "credit-suspend-release-" + reservation, now);

            JdbcCreditBoundary boundary = creditBoundary(database);
            boundary.releaseForCommitment(tenant, workspace, commitment, null);

            assertThat(database.queryForObject("select status from payments.credit_reservation "
                            + "where tenant_id=? and workspace_id=? and id=?", String.class,
                    tenant, workspace, reservation)).isEqualTo("RELEASED");
            assertThat(database.queryForObject("select reserved_exposure from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and id=?", BigDecimal.class,
                    tenant, workspace, creditAccount)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThatThrownBy(() -> inTenantTransaction(database, () -> boundary.reserveForCommitment(tenant,
                    workspace, account, secondCommitment, null, null, amount, "PEN", Instant.now())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Client credit account is not configured");
            assertThat(database.queryForObject("select count(*) from payments.credit_reservation "
                            + "where tenant_id=? and workspace_id=? and commercial_commitment_id=?",
                    Integer.class, tenant, workspace, secondCommitment)).isZero();
        } finally {
            database.update("delete from payments.credit_reservation where tenant_id=? and workspace_id=? "
                    + "and commercial_commitment_id in (?,?)", tenant, workspace, commitment, secondCommitment);
            database.update("delete from payments.credit_account where tenant_id=? and workspace_id=? "
                    + "and id=?", tenant, workspace, creditAccount);
            database.update("delete from sales.commercial_commitment where tenant_id=? and workspace_id=? "
                    + "and id in (?,?)", tenant, workspace, commitment, secondCommitment);
            database.update("delete from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, account);
        }
    }

    @Test
    void commitmentReservationRequiresCreditCustomerTerms() {
        UUID tenant = UUID.fromString(tenantId());
        UUID workspace = UUID.fromString(workspaceId());
        UUID account = UUID.randomUUID();
        UUID commitment = UUID.randomUUID();
        UUID creditAccount = UUID.randomUUID();
        String suffix = account.toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
        String taxValue = "20" + String.format(java.util.Locale.ROOT, "%09d",
                Math.floorMod(account.getLeastSignificantBits(), 1_000_000_000L));
        JdbcTemplate database = tenantJdbc();
        Timestamp now = Timestamp.from(Instant.now());
        BigDecimal amount = new BigDecimal("10.00");

        try {
            insertClientAccount(database, tenant, workspace, account, suffix, taxValue, now, "CASH");
            insertCommitment(database, tenant, workspace, account, commitment, now);
            database.update("insert into payments.credit_account "
                            + "(id,tenant_id,workspace_id,client_account_id,currency,credit_limit,status,created_at,updated_at) "
                            + "values (?,?,?,?,'PEN',?,'ACTIVE',?,?)",
                    creditAccount, tenant, workspace, account, new BigDecimal("1000.00"), now, now);

            JdbcCreditBoundary boundary = creditBoundary(database);
            assertThatThrownBy(() -> inTenantTransaction(database, () -> boundary.reserveForCommitment(tenant,
                    workspace, account, commitment, null, null, amount, "PEN", Instant.now())))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Credit commitment is not allowed by customer terms");
            assertThat(database.queryForObject("select reserved_exposure from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and id=?", BigDecimal.class,
                    tenant, workspace, creditAccount)).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(database.queryForObject("select count(*) from payments.credit_reservation "
                            + "where tenant_id=? and workspace_id=? and commercial_commitment_id=?",
                    Integer.class, tenant, workspace, commitment)).isZero();

            database.update("update sales.client_account set payment_condition='CREDIT_30',version=version+1 "
                            + "where tenant_id=? and workspace_id=? and id=?", tenant, workspace, account);
            inTenantTransaction(database, () -> boundary.reserveForCommitment(tenant, workspace, account,
                    commitment, null, null, amount, "PEN", Instant.now()));
            assertThat(database.queryForObject("select reserved_exposure from payments.credit_account "
                            + "where tenant_id=? and workspace_id=? and id=?", BigDecimal.class,
                    tenant, workspace, creditAccount)).isEqualByComparingTo(amount);
            assertThat(database.queryForObject("select status from payments.credit_reservation "
                            + "where tenant_id=? and workspace_id=? and commercial_commitment_id=?", String.class,
                    tenant, workspace, commitment)).isEqualTo("RESERVED");
        } finally {
            database.update("delete from payments.credit_reservation where tenant_id=? and workspace_id=? "
                    + "and commercial_commitment_id=?", tenant, workspace, commitment);
            database.update("delete from payments.credit_account where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, creditAccount);
            database.update("delete from sales.commercial_commitment where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, commitment);
            database.update("delete from sales.client_account where tenant_id=? and workspace_id=? and id=?",
                    tenant, workspace, account);
        }
    }

    private static void insertClientAccount(JdbcTemplate database, UUID tenant, UUID workspace, UUID account,
            String suffix, String taxValue, Timestamp now) {
        insertClientAccount(database, tenant, workspace, account, suffix, taxValue, now, "CREDIT_30");
    }

    private static void insertClientAccount(JdbcTemplate database, UUID tenant, UUID workspace, UUID account,
            String suffix, String taxValue, Timestamp now, String paymentCondition) {
        database.update("insert into sales.client_account "
                        + "(id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,"
                        + "tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,"
                        + "delivery_profile,payment_condition,status,credit_limit,current_commercial_exposure,"
                        + "available_credit,credit_currency,created_at,updated_at,version) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
                account, tenant, workspace, "CR-" + suffix, "Synthetic Credit Buyer " + suffix,
                "Synthetic Credit Buyer " + suffix, "PE", "RUC", taxValue, "SYNTHETIC", "Fixture Contact",
                "credit-fk-" + suffix.toLowerCase(java.util.Locale.ROOT) + "@example.test", "+51999999999",
                "Synthetic fixture", paymentCondition, "ACTIVE", new BigDecimal("1000.00"), BigDecimal.ZERO,
                new BigDecimal("1000.00"), "PEN", now, now);
    }

    private static void insertCommitment(JdbcTemplate database, UUID tenant, UUID workspace, UUID account,
            UUID commitment, Timestamp now) {
        database.update("insert into sales.commercial_commitment "
                        + "(id,tenant_id,workspace_id,purchase_request_id,sales_order_id,client_account_id,"
                        + "status,created_at,updated_at,version,origin_type) "
                        + "values (?,?,?,null,null,?,'ACTIVE',?,?,0,'DIRECT_ORDER')",
                commitment, tenant, workspace, account, now, now);
    }

    private static JdbcCreditBoundary creditBoundary(JdbcTemplate database) {
        var accounts = new ClientAccountPersistenceAdapter(database);
        return new JdbcCreditBoundary(database, TenantCreditEligibilitySourceFactory.bindTo(accounts));
    }

    private static void inTenantTransaction(JdbcTemplate database, Runnable action) {
        new TransactionTemplate(new DataSourceTransactionManager(database.getDataSource()))
                .executeWithoutResult(status -> action.run());
    }
}
