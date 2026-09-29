package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * BC-07 receivable facts used by payment workflows. A payment claim serializes
 * against settlement and financial adjustment in the caller's local transaction.
 * The claim must finish before calling an external payment provider.
 */
public interface ReceivablePaymentAccess {
    Snapshot claimForPayment(UUID tenantId, UUID workspaceId, UUID receivableId);
    Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID receivableId);
    Optional<Snapshot> findForSubject(UUID tenantId, UUID workspaceId, UUID subjectId, String subjectType);
    Page list(UUID tenantId, UUID workspaceId, UUID customerAccountId, int page, int size);
    Map<UUID, Snapshot> findAll(UUID tenantId, UUID workspaceId, List<UUID> receivableIds);
    BigDecimal allocatedAmount(UUID tenantId, UUID workspaceId, UUID paymentId);

    record Snapshot(UUID id, UUID clientAccountId, String subjectType, UUID subjectId,
                    String number, String currency, BigDecimal amount, BigDecimal amountPaid,
                    BigDecimal adjustmentTotal, String status, Instant dueAt, long version,
                    Instant createdAt) {
        public BigDecimal payableAmount() { return amount.add(adjustmentTotal).subtract(amountPaid); }
    }

    record Page(List<Snapshot> values, long total) {
        public Page { values = List.copyOf(values); }
    }
}
