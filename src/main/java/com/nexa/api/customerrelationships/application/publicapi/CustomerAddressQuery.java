package com.nexa.api.customerrelationships.application.publicapi;

import com.nexa.api.customerrelationships.contract.CustomerAddressReference;

import java.util.Optional;

/** Public address snapshot lookup. Every method returns ACTIVE addresses of ACTIVE accounts only. */
public interface CustomerAddressQuery {
    Optional<CustomerAddressReference> findReference(
            String tenantId, String workspaceId, String customerAccountId, String addressId);

    Optional<CustomerAddressReference> findBuyerReference(
            String tenantId, String workspaceId, String membershipId, String addressId);

    Optional<CustomerAddressReference> findDefaultBuyerReference(
            String tenantId, String workspaceId, String membershipId);
}
