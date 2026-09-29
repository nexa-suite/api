package com.nexa.api.payments.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable Payments-owned facts required to render payment documents. */
public interface PaymentDocumentSourceQuery {
    Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID paymentId);

    record Snapshot(UUID id, UUID customerAccountId, UUID receivableId, BigDecimal amount,
                    String currency, String method, String status, String providerReference,
                    Instant createdAt) { }
}
