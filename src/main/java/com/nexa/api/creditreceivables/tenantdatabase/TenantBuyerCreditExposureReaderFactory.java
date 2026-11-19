package com.nexa.api.creditreceivables.tenantdatabase;

import com.nexa.api.creditreceivables.application.publicapi.BuyerCreditExposureReader;
import com.nexa.api.creditreceivables.application.publicapi.CreditEligibilitySource;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds the Buyer credit read to the verified Tenant JDBC session. */
public interface TenantBuyerCreditExposureReaderFactory {
    BuyerCreditExposureReader bindBuyerExposureTo(JdbcTemplate jdbc, CustomerAccountQuery accounts,
            CreditEligibilitySource eligibility);
}
