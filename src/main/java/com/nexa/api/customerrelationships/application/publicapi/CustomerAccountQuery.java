package com.nexa.api.customerrelationships.application.publicapi;

import java.util.Optional;

/** Published query contract for contexts that only need account identity and eligibility. */
public interface CustomerAccountQuery {
    Optional<CustomerAccountReference> findReference(String tenantId, String workspaceId, String customerAccountId);

    Optional<CustomerAccountReference> findBuyerReference(String tenantId, String workspaceId, String membershipId);
}
