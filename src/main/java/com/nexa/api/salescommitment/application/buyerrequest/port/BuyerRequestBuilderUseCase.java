package com.nexa.api.salescommitment.application.buyerrequest.port;

import com.nexa.api.salescommitment.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.salescommitment.application.buyerrequest.model.CreateBuyerRequestCommand;
import com.nexa.api.salescommitment.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

public interface BuyerRequestBuilderUseCase {
    BuyerRequestSnapshot preview(CurrentAccessContext context, CreateBuyerRequestCommand command);

    BuyerRequestView create(CurrentAccessContext context, CreateBuyerRequestCommand command);
}
