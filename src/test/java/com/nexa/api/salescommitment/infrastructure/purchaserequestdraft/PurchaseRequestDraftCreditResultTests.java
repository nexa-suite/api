package com.nexa.api.salescommitment.infrastructure.purchaserequestdraft;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery.CustomerTermsSnapshot;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery.CreditExposureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PurchaseRequestDraftCreditResultTests {
    @Test
    void creditIsAvailableOnlyForCreditTermsAndActiveSufficientExposure() {
        CreditExposureSnapshot exposure = new CreditExposureSnapshot("PEN", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);

        assertThat(PurchaseRequestDraftService.creditResult(true, new CustomerTermsSnapshot("CREDIT_30"),
                exposure, new BigDecimal("40.00"))).isEqualTo("AVAILABLE");
        assertThat(PurchaseRequestDraftService.creditResult(true, new CustomerTermsSnapshot("CASH"),
                exposure, new BigDecimal("40.00"))).isEqualTo("UNAVAILABLE");
        assertThat(PurchaseRequestDraftService.creditResult(true, null, exposure,
                new BigDecimal("40.00"))).isEqualTo("UNAVAILABLE");
        assertThat(PurchaseRequestDraftService.creditResult(true, new CustomerTermsSnapshot("CREDIT_30"),
                new CreditExposureSnapshot("PEN", new BigDecimal("100.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, false), new BigDecimal("40.00")))
                .isEqualTo("UNAVAILABLE");
        assertThat(PurchaseRequestDraftService.creditResult(true, new CustomerTermsSnapshot("CREDIT_30"),
                exposure, new BigDecimal("100.01"))).isEqualTo("UNAVAILABLE");
        assertThat(PurchaseRequestDraftService.creditResult(true, new CustomerTermsSnapshot("CREDIT_30"),
                exposure, BigDecimal.ZERO)).isEqualTo("UNAVAILABLE");
    }

    @Test
    void nonCreditPaymentDoesNotRequireCreditEligibility() {
        assertThat(PurchaseRequestDraftService.creditResult(false, null, null, BigDecimal.ZERO))
                .isEqualTo("NOT_APPLICABLE");
    }
}
