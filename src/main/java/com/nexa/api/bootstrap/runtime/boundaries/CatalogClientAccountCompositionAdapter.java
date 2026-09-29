package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.CatalogClientAccountPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/** Adapts owner-published Customer Relationships lookups into Catalog's buyer profile port. */
@Repository
@Profile("!test")
public class CatalogClientAccountCompositionAdapter implements CatalogClientAccountPort {
    private final CustomerAccountQuery customerAccounts;

    public CatalogClientAccountCompositionAdapter(CustomerAccountQuery customerAccounts) {
        this.customerAccounts = customerAccounts;
    }

    @Override
    public Optional<UUID> findForMembership(UUID tenantId, UUID workspaceId, UUID membershipId) {
        return customerAccounts.findUnfilteredReferenceForMembership(
                tenantId.toString(), workspaceId.toString(), membershipId.toString()).map(UUID::fromString);
    }

    @Override
    public Optional<ClientAccountProfile> findProfileForMembership(
            UUID tenantId, UUID workspaceId, UUID membershipId) {
        return customerAccounts.findActiveBuyerDetails(
                tenantId.toString(), workspaceId.toString(), membershipId.toString()).map(this::profile);
    }

    @Override
    public Optional<ClientAccountProfile> findActiveProfile(
            UUID tenantId, UUID workspaceId, UUID customerAccountId) {
        return customerAccounts.findActiveDetails(
                tenantId.toString(), workspaceId.toString(), customerAccountId.toString()).map(this::profile);
    }

    private ClientAccountProfile profile(CustomerAccountDetails account) {
        return new ClientAccountProfile(UUID.fromString(account.id()), account.segment(), null);
    }
}
