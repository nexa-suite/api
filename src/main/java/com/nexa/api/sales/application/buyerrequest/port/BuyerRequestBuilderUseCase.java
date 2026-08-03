package com.nexa.api.sales.application.buyerrequest.port;

import com.nexa.api.sales.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface BuyerRequestBuilderUseCase {
    BuyerRequestSnapshot preview(CurrentAccessContext context, CreateBuyerRequestCommand command);

    BuyerRequestView create(CurrentAccessContext context, CreateBuyerRequestCommand command);
}
