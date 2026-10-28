package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CentralCreditEligibilitySourceConfigurationTests {
    private static final String TENANT_ID = "tenant-a";
    private static final String WORKSPACE_ID = "workspace-a";
    private static final String ACCOUNT_ID = "account-a";

    @Test
    void centralSourceUsesTheScopedCentralCustomerQueryForS3AndLocalProfiles() {
        for (String profile : new String[] { "s3", "local" }) {
            AccountQueryWithTerms centralAccounts = new AccountQueryWithTerms("CREDIT-30");
            contextRunner(profile, centralAccounts).run(context -> {
                assertThat(context).hasNotFailed();
                CreditEligibilitySource source = context.getBean(CreditEligibilitySource.class);

                assertThat(source.isCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isTrue();
                assertThat(centralAccounts.lastTermsScope)
                        .isEqualTo(new Scope(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID));
                assertThat(source.lockAndCheckCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isTrue();
                assertThat(centralAccounts.lastLockScope)
                        .isEqualTo(new Scope(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID));
            });
        }
    }

    @Test
    void centralSourceDeniesEligibilityWhenCustomerTermsQueryIsUnavailable() {
        AccountQueryWithoutTerms centralAccounts = new AccountQueryWithoutTerms();

        contextRunner("s3", centralAccounts).run(context -> {
            assertThat(context).hasNotFailed();
            CreditEligibilitySource source = context.getBean(CreditEligibilitySource.class);

            assertThat(source.isCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isFalse();
            assertThat(source.lockAndCheckCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isFalse();
            assertThat(centralAccounts.lastLockScope)
                    .isEqualTo(new Scope(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID));
        });
    }

    @Test
    void localTenantCompositionBindsEligibilityToItsTransactionQuery() {
        AccountQueryWithTerms centralAccounts = new AccountQueryWithTerms("CREDIT-30");
        AccountQueryWithTerms tenantAccounts = new AccountQueryWithTerms("CASH");

        contextRunner("local", centralAccounts).run(context -> {
            assertThat(context).hasNotFailed();
            CreditEligibilitySource centralSource = context.getBean(CreditEligibilitySource.class);
            CreditEligibilitySource tenantSource = TenantCreditEligibilitySourceFactory.bindTo(tenantAccounts);

            assertThat(centralSource.isCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isTrue();
            assertThat(tenantSource.isCreditAllowed(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID)).isFalse();
            assertThat(centralAccounts.lastTermsScope)
                    .isEqualTo(new Scope(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID));
            assertThat(tenantAccounts.lastTermsScope)
                    .isEqualTo(new Scope(TENANT_ID, WORKSPACE_ID, ACCOUNT_ID));
        });
    }

    private static ApplicationContextRunner contextRunner(String profile, CustomerAccountQuery centralAccounts) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + profile)
                .withUserConfiguration(CentralCreditEligibilitySourceConfiguration.class)
                .withBean(CustomerAccountQuery.class, () -> centralAccounts);
    }

    private abstract static class AccountQuery implements CustomerAccountQuery {
        protected Scope lastTermsScope;
        protected Scope lastLockScope;

        @Override
        public Optional<CustomerAccountReference> findReference(String tenantId, String workspaceId,
                String customerAccountId) {
            return Optional.empty();
        }

        @Override
        public Optional<CustomerAccountReference> lockReference(String tenantId, String workspaceId,
                String customerAccountId) {
            lastLockScope = new Scope(tenantId, workspaceId, customerAccountId);
            return Optional.of(new CustomerAccountReference(customerAccountId, "ACTIVE"));
        }

        @Override
        public Optional<CustomerAccountReference> findBuyerReference(String tenantId, String workspaceId,
                String membershipId) {
            return Optional.empty();
        }

        @Override
        public Optional<String> findUnfilteredReferenceForMembership(String tenantId, String workspaceId,
                String membershipId) {
            return Optional.empty();
        }
    }

    private static final class AccountQueryWithTerms extends AccountQuery implements CustomerTermsQuery {
        private final String termsCode;

        private AccountQueryWithTerms(String termsCode) {
            this.termsCode = termsCode;
        }

        @Override
        public Optional<CustomerTermsSnapshot> findTerms(String tenantId, String workspaceId,
                String customerAccountId) {
            lastTermsScope = new Scope(tenantId, workspaceId, customerAccountId);
            return Optional.of(new CustomerTermsSnapshot(termsCode));
        }
    }

    private static final class AccountQueryWithoutTerms extends AccountQuery { }

    private record Scope(String tenantId, String workspaceId, String accountId) { }
}
