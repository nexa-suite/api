package com.nexa.api.salescommitment.application.publicapi;

import java.util.UUID;

/** Sales-owned usage facts for tenant plan configuration. */
public interface SalesUsageQuery {
    long countTransactions(UUID tenantId);
}
