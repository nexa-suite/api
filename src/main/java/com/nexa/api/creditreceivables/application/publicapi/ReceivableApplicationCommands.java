package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-07 settlement boundary; payment identity is carried as a stable ID. */
public interface ReceivableApplicationCommands {
    Result apply(Request request);

    record Request(UUID tenantId, UUID workspaceId, UUID actorMembershipId,
                   UUID receivableId, UUID paymentId, BigDecimal amount,
                   String currency, String occurrenceKey, Instant now) {
        public Request {
            if (tenantId == null || workspaceId == null || actorMembershipId == null || receivableId == null
                    || paymentId == null || amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()
                    || occurrenceKey == null || occurrenceKey.isBlank() || now == null) {
                throw new IllegalArgumentException("Receivable application request is incomplete");
            }
            currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
            occurrenceKey = occurrenceKey.trim();
        }
    }

    record Result(UUID applicationId, UUID receivableId, UUID paymentId,
                  BigDecimal amountPaid, BigDecimal outstandingAmount, String status,
                  long receivableVersion) { }
}
