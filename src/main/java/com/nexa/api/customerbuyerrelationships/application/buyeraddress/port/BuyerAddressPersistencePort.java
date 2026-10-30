package com.nexa.api.customerbuyerrelationships.application.buyeraddress.port;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.customerbuyerrelationships.domain.model.buyeraddress.BuyerAddress;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-local persistence boundary for addresses owned by one Buyer membership. */
public interface BuyerAddressPersistencePort {
    List<BuyerAddress> list(String tenantId, String workspaceId, String buyerMembershipId);

    Optional<BuyerAddress> find(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId);

    void insert(BuyerAddress address, long nowEpochMillis);

    int update(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
               String label, Address address, long expectedVersion);

    int setDefault(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
                   long expectedVersion, long nowEpochMillis);

    int deactivate(String tenantId, String workspaceId, String buyerMembershipId, UUID addressId,
                   long expectedVersion, long nowEpochMillis);

    void installMembershipScope(String buyerMembershipId);
}
