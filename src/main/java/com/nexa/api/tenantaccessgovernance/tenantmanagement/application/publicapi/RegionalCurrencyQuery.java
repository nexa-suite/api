package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import java.util.Optional;
import java.util.UUID;

public interface RegionalCurrencyQuery {
    Optional<String> findCurrency(UUID tenantId);
}
