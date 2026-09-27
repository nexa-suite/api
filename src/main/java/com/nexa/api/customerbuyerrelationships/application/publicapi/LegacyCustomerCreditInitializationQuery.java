package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable legacy initialization facts attached to a customer relationship.
 * Credit account limits, exposure and reservation authority remain in BC-07.
 */
public interface LegacyCustomerCreditInitializationQuery {
    Optional<Snapshot> find(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency);
    record Snapshot(UUID customerAccountId, String currency, BigDecimal initialLimit) { }
}
