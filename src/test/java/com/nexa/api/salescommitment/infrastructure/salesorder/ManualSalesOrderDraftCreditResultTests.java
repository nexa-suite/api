package com.nexa.api.salescommitment.infrastructure.salesorder;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery.CustomerTermsSnapshot;
import com.nexa.api.creditreceivables.application.publicapi.CreditExposureQuery.CreditExposureSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ManualSalesOrderDraftCreditResultTests {
    @Test
    void creditIsAvailableOnlyWhenActiveBalanceCoversPricedLines() {
        assertThat(result(true, "100.00", "40.00")).isEqualTo("AVAILABLE");
        assertThat(result(true, "100.00", "100.01")).isEqualTo("UNAVAILABLE");
    }

    @Test
    void zeroBalanceInactiveAccountAndUnpricedDraftCannotClaimCreditAvailable() {
        assertThat(result(true, "0.00", "10.00")).isEqualTo("UNAVAILABLE");
        assertThat(result(false, "100.00", "10.00")).isEqualTo("UNAVAILABLE");
        assertThat(result(true, "100.00", "0.00")).isEqualTo("UNAVAILABLE");
    }

    @Test
    void cashOrMissingTermsCannotClaimCreditAvailable() {
        CreditExposureSnapshot activeWithBalance = new CreditExposureSnapshot("PEN", new BigDecimal("100.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);

        assertThat(ManualSalesOrderDraftPersistenceAdapter.creditResult(PaymentOption.CREDIT_LINE,
                new CustomerTermsSnapshot("CASH"), activeWithBalance, new BigDecimal("10.00")))
                .isEqualTo("UNAVAILABLE");
        assertThat(ManualSalesOrderDraftPersistenceAdapter.creditResult(PaymentOption.CREDIT_LINE,
                null, activeWithBalance, new BigDecimal("10.00")))
                .isEqualTo("UNAVAILABLE");
        assertThat(ManualSalesOrderDraftPersistenceAdapter.creditResult(PaymentOption.CREDIT_LINE,
                new CustomerTermsSnapshot("CREDIT_30"), activeWithBalance, new BigDecimal("10.00")))
                .isEqualTo("AVAILABLE");
    }

    @Test
    void nonCreditPaymentDoesNotRequireCreditAvailability() {
        assertThat(ManualSalesOrderDraftPersistenceAdapter.creditResult(PaymentOption.CASH, null,
                CreditExposureSnapshot.unavailable("PEN"), BigDecimal.ZERO)).isEqualTo("NOT_APPLICABLE");
    }

    private static String result(boolean active, String available, String pricedTotal) {
        BigDecimal balance = new BigDecimal(available);
        CreditExposureSnapshot exposure = new CreditExposureSnapshot("PEN", balance, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, active);
        return ManualSalesOrderDraftPersistenceAdapter.creditResult(PaymentOption.CREDIT_LINE,
                new CustomerTermsSnapshot("CREDIT_30"), exposure, new BigDecimal(pricedTotal));
    }
}
