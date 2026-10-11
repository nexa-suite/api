package com.nexa.api.salescommitment.presentation.salesorder.response;

import java.util.List;

public record SalesOrderWalletRefundCandidatePageResponse(List<SalesOrderWalletRefundCandidateResponse> items,
        int page, int size, long total) {
    public SalesOrderWalletRefundCandidatePageResponse { items = List.copyOf(items); }
}
