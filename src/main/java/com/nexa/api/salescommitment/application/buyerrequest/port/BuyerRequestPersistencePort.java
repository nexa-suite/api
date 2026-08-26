package com.nexa.api.salescommitment.application.buyerrequest.port;

import com.nexa.api.salescommitment.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.salescommitment.domain.model.buyerrequest.BuyerRequest;

import java.util.Optional;

public interface BuyerRequestPersistencePort {
    BuyerRequestView save(BuyerRequest request, String tenantId, String workspaceId,
                          String code, long nowEpochMillis);

    Optional<BuyerRequestView> find(String tenantId, String workspaceId, String requestId);
}
