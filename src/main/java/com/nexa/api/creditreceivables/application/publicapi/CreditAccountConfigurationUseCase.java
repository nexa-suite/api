package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Tenant-routed application boundary for BOM/Owner credit-account setup. */
public interface CreditAccountConfigurationUseCase {
    CustomerAccountDirectoryQuery.Page candidates(CurrentAccessContext context, String search, String status,
                                                   int page, int size);

    CreditAccountConfigurationQuery.Snapshot find(CurrentAccessContext context, UUID customerAccountId,
                                                    String currency);

    CreditAccountConfigurationQuery.Snapshot configure(CurrentAccessContext context, UUID customerAccountId,
                                                         String currency, BigDecimal creditLimit, boolean active,
                                                         boolean createIfAbsent, Long expectedVersion,
                                                         String idempotencyKey, String requestHash, Instant now);
}
