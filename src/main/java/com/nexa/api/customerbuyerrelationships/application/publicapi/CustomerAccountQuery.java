package com.nexa.api.customerbuyerrelationships.application.publicapi;

import java.util.Optional;
import java.util.List;

/**
 * Published Customer Relationships lookup. {@code findReference} methods return ACTIVE accounts only;
 * lock and historical methods explicitly document their status behavior.
 */
public interface CustomerAccountQuery {
    Optional<CustomerAccountReference> findReference(String tenantId, String workspaceId, String customerAccountId);

    /** Locks the exact scoped account row until the current transaction completes, without a status filter. */
    default Optional<CustomerAccountReference> lockReference(
            String tenantId, String workspaceId, String customerAccountId) {
        return Optional.empty();
    }

    /** Active account reference visible to the exact Sales Representative membership. */
    default Optional<CustomerAccountReference> findSalesOwnerReference(
            String tenantId, String workspaceId, String customerAccountId, String salesOwnerMembershipId) {
        return Optional.empty();
    }

    /**
     * Locks and returns the active client accounts assigned to this exact Sales membership.
     * The lock is held until the caller's current transaction completes so dependent reads can
     * apply the assignment snapshot atomically with dispatch visibility.
     */
    default List<String> lockActiveSalesOwnerAccountIds(
            String tenantId, String workspaceId, String salesOwnerMembershipId) {
        return List.of();
    }

    Optional<CustomerAccountReference> findBuyerReference(String tenantId, String workspaceId, String membershipId);

    /**
     * Acquires the account-level Buyer payment-setup authorization lock and checks that the
     * account and exact Buyer relationship are still active. Implementations must use the
     * caller's current transaction so revocation and provider callbacks serialize together.
     */
    default boolean lockAndCheckActiveBuyerPaymentSetupRelationship(
            String tenantId, String workspaceId, String membershipId, String customerAccountId) {
        throw new UnsupportedOperationException("Buyer payment-setup authorization is not configured");
    }

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
