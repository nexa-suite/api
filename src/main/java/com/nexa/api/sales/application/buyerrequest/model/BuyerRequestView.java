package com.nexa.api.sales.application.buyerrequest.model;

import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot;

import java.util.List;

public record BuyerRequestView(String id, String code, String tenantId, String workspaceId,
                               String clientAccountId, String buyerMembershipId, String status,
                               BuyerRequestSnapshot snapshot, List<PurchaseRequestLineView> lines, long version) {
    public BuyerRequestView {
        lines = List.copyOf(lines);
    }
}
