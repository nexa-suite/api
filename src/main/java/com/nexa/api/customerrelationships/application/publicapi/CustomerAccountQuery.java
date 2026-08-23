package com.nexa.api.customerrelationships.application.publicapi;

import java.util.Optional;
import java.util.List;

/**
 * Published Customer Relationships lookup. Reference methods return ACTIVE accounts only;
 * historical details are explicit and may include suspended accounts.
 */
public interface CustomerAccountQuery {
    Optional<CustomerAccountReference> findReference(String tenantId, String workspaceId, String customerAccountId);

    Optional<CustomerAccountReference> findBuyerReference(String tenantId, String workspaceId, String membershipId);

    default Optional<CustomerAccountDetails> findActiveDetails(
            String tenantId, String workspaceId, String customerAccountId) {
        return Optional.empty();
    }

    default Optional<CustomerAccountDetails> findActiveBuyerDetails(
            String tenantId, String workspaceId, String membershipId) {
        return Optional.empty();
    }

    default Optional<CustomerAccountDetails> findHistoricalDetails(
            String tenantId, String workspaceId, String customerAccountId) {
        return Optional.empty();
    }

    default List<String> findHistoricalIdsMatching(String tenantId, String workspaceId, String search) {
        return List.of();
    }
}
