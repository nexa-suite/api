package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-07 credit exposure operation used by the BC-08 payment flow. */
public interface CreditPaymentCommands {
    Result apply(ResultRequest request);

    record ResultRequest(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                         UUID clientAccountId, UUID receivableId, UUID paymentId,
                         BigDecimal amount, String currency, String idempotencyKey,
                         Instant now) {
        public ResultRequest {
            if (tenantId == null || workspaceId == null || actorMembershipId == null || clientAccountId == null
                    || receivableId == null || paymentId == null || amount == null || amount.signum() <= 0
                    || currency == null || currency.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()
                    || now == null) throw new IllegalArgumentException("Credit payment request is incomplete");
            currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
            idempotencyKey = idempotencyKey.trim();
        }
    }

    record Result(UUID creditAccountId, UUID reservationId, BigDecimal exposure,
                  String status) { }
}
