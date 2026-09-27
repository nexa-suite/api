package com.nexa.api.customerbuyerrelationships.application.publicapi;

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

    /** Relationship facts without an account-status filter, for immutable evidence access. */
    default List<String> findRelatedAccountIds(String tenantId, String workspaceId, String membershipId) {
        throw new UnsupportedOperationException("Buyer relationship query is not configured");
    }

    /** First native relationship row, without account-status filtering or ordering. */
    Optional<String> findUnfilteredReferenceForMembership(
            String tenantId, String workspaceId, String membershipId);

    default boolean hasBuyerRelationship(String tenantId, String workspaceId, String membershipId, String customerAccountId) {
        return findRelatedAccountIds(tenantId, workspaceId, membershipId).contains(customerAccountId);
    }

    default List<String> findHistoricalIdsMatching(String tenantId, String workspaceId, String search) {
        return List.of();
    }
}
