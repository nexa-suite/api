package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;

/** Authorized BC-07 exposure reads for the HTTP boundary. */
public interface CreditExposureUseCase {
    CreditExposureView read(CurrentAccessContext context, String clientAccountId, String currency);

    CreditExposureView readBuyer(CurrentAccessContext context, String currency);

    record CreditExposureView(String clientAccountId, String currency, BigDecimal creditLimit,
                              BigDecimal ledgerExposure, BigDecimal outstandingReceivables,
                              BigDecimal reservedExposure, BigDecimal used, BigDecimal availableCredit,
                              boolean active, Instant asOf) { }
}
