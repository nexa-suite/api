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
}
