package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.creditreceivables.application.publicapi.CreditExposureUseCase.CreditExposureView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

@FunctionalInterface
public interface BuyerCreditExposureReader {
    CreditExposureView readBuyer(CurrentAccessContext context, String currency);
}
