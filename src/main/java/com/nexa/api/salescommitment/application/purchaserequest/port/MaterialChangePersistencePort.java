package com.nexa.api.salescommitment.application.purchaserequest.port;

import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeTerms;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;

import java.util.Optional;

public interface MaterialChangePersistencePort {
    MaterialChangeProposalView propose(String tenantId, String workspaceId, String purchaseRequestId,
            long expectedVersion, String actorMembershipId, String reason, MaterialChangeTerms original,
            MaterialChangeTerms proposed, long nowEpochMillis);

    Optional<MaterialChangeProposalView> findCurrent(String tenantId, String workspaceId,
            String buyerAccountId, String purchaseRequestId);

    PurchaseRequestView accept(String tenantId, String workspaceId, String buyerAccountId,
            String purchaseRequestId, String proposalId, long expectedVersion, String actorMembershipId,
            MaterialChangeTerms acceptedTerms, long nowEpochMillis);

}
