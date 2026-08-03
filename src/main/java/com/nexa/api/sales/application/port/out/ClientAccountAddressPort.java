package com.nexa.api.sales.application.port.out;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountAddress;

import java.util.Optional;

/** Narrow ACL for scoped address reads; it never exposes another bounded context's aggregate. */
public interface ClientAccountAddressPort {
    Optional<ClientAccountAddress> find(String tenantId, String workspaceId, String clientAccountId, String addressId);

    Optional<ClientAccountAddress> findForBuyer(String tenantId, String workspaceId, String membershipId, String addressId);

    Optional<ClientAccountAddress> findDefaultForBuyer(String tenantId, String workspaceId, String membershipId);
}
