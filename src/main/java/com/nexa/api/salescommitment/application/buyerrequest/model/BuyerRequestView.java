package com.nexa.api.salescommitment.application.buyerrequest.model;

import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.salescommitment.domain.model.buyerrequest.BuyerRequestSnapshot;

import java.util.List;

public record BuyerRequestView(String id, String code, String tenantId, String workspaceId,
                               String clientAccountId, String buyerMembershipId, String status,
                               BuyerRequestSnapshot snapshot, List<PurchaseRequestLineView> lines, long version) {
    public BuyerRequestView {
        lines = List.copyOf(lines);
    }
}
