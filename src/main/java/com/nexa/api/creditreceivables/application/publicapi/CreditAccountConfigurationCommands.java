package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** BC-07 credit-limit and account-state command, called within a Tenant transaction. */
public interface CreditAccountConfigurationCommands {
    CreditAccountConfigurationQuery.Snapshot configure(Request request);

    record Request(UUID tenantId, UUID workspaceId, UUID customerAccountId, UUID actorMembershipId,
                   String currency, BigDecimal creditLimit, boolean active, boolean createIfAbsent,
                   Long expectedVersion, String idempotencyKey, String requestHash, Instant now) {
        public Request {
            if (tenantId == null || workspaceId == null || customerAccountId == null || actorMembershipId == null
                    || currency == null || !currency.matches("[A-Z]{3}") || creditLimit == null
                    || creditLimit.signum() < 0 || creditLimit.scale() > 4
                    || idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160
                    || requestHash == null || !requestHash.matches("[0-9a-f]{64}") || now == null) {
                throw new IllegalArgumentException("Credit configuration command is incomplete");
            }
            if (createIfAbsent && expectedVersion != null || !createIfAbsent && expectedVersion == null) {
                throw new IllegalArgumentException("Credit configuration precondition is inconsistent");
            }
            if (expectedVersion != null && expectedVersion < 0) {
                throw new IllegalArgumentException("Expected credit configuration version is invalid");
            }
            idempotencyKey = idempotencyKey.trim();
        }
    }
}
