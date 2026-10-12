package com.nexa.api.salescommitment.application.publicapi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Narrow BC-04 financial state needed to authorize an explicit wallet refund. */
@NamedInterface("sales-public")
public interface SalesOrderFinancialStateQuery {
    Optional<State> findWalletRefundState(UUID tenantId, UUID workspaceId, UUID salesOrderId);

    CandidatePage findCancelledWalletRefundCandidates(UUID tenantId, UUID workspaceId, int page, int size);

    record State(String status, String paymentOption) { }

    record Candidate(UUID salesOrderId, String orderNumber) { }

    record CandidatePage(List<Candidate> items, int page, int size, long total) {
        public CandidatePage { items = List.copyOf(items); }
    }
}
