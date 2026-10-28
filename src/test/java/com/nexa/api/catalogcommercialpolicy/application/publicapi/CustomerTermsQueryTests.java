package com.nexa.api.catalogcommercialpolicy.application.publicapi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerTermsQueryTests {
    @Test
    void commercialPolicyOwnsLegacyTermTranslation() {
        var cash = new CustomerTermsQuery.CustomerTermsSnapshot(" cash ");
        var net30 = new CustomerTermsQuery.CustomerTermsSnapshot("NET 30");

        assertThat(cash.code()).isEqualTo("cash");
        assertThat(cash.creditAllowed()).isFalse();
        assertThat(cash.dueDays()).isZero();
        assertThat(net30.creditAllowed()).isTrue();
        assertThat(net30.dueDays()).isEqualTo(30);
    }

    @Test
    void recognizesOnlyCompleteCreditTerms() {
        for (String code : new String[]{"credit_15", "CREDIT_30", "NET30", "NET-30", "NET_30", "NET 30"}) {
            assertThat(new CustomerTermsQuery.CustomerTermsSnapshot(code).creditAllowed())
                    .as("credit term %s", code)
                    .isTrue();
        }

        for (String code : new String[]{
                "NO_CREDIT", "NOT_NET_30", "CASH_CREDIT", "CASH_NET_30", "credit_limit",
                "CREDIT_30_EXTRA", "NET99999999999999999999"
        }) {
            var terms = new CustomerTermsQuery.CustomerTermsSnapshot(code);
            assertThat(terms.creditAllowed()).as("non-credit term %s", code).isFalse();
            assertThat(terms.dueDays()).as("due days for %s", code).isZero();
        }
    }
}
