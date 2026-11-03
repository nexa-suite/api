package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountRegistrationResult;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.Objects;

/** Exposes one routed read; neither management reads nor mutations can reach the delegate. */
final class CloudBuyerSelfAccountReadUseCase implements ClientAccountUseCase {
    private final ClientAccountUseCase tenantDelegate;

    CloudBuyerSelfAccountReadUseCase(ClientAccountUseCase tenantDelegate) {
        this.tenantDelegate = Objects.requireNonNull(tenantDelegate, "Tenant Buyer account read is required");
    }

    @Override
    public ClientAccountView buyerDetail(CurrentAccessContext context) {
        return tenantDelegate.buyerDetail(context);
    }

    @Override public CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context,
            String search, String status, int page, int size) { throw unavailable(); }
    @Override public ClientAccountView detail(CurrentAccessContext context, String id) { throw unavailable(); }
    @Override public ClientAccountRegistrationResult registerOwnBusiness(CurrentAccessContext context,
            BuyerClientAccountRegistrationCommand command) { throw unavailable(); }
    @Override public List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context) { throw unavailable(); }
    @Override public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) { throw unavailable(); }
    @Override public ClientAccountView update(CurrentAccessContext context, String id,
            ClientAccountView command, long version) { throw unavailable(); }
    @Override public ClientAccountView changeStatus(CurrentAccessContext context, String id,
            String status, long version) { throw unavailable(); }
    @Override public ClientAccountView associateBuyer(CurrentAccessContext context, String id,
            String membershipId, long version) { throw unavailable(); }
    @Override public ClientAccountBuyerMembershipPage buyerMemberships(CurrentAccessContext context,
            String id, int page, int size) { throw unavailable(); }
    @Override public ClientAccountBuyerMembershipMutation associateBuyerMembership(CurrentAccessContext context,
            String id, String membershipId, long version) { throw unavailable(); }
    @Override public ClientAccountBuyerMembershipMutation deactivateBuyerMembership(CurrentAccessContext context,
            String id, String membershipId, long version) { throw unavailable(); }
    @Override public ClientAccountSalesOwnerAssignment assignSalesOwner(CurrentAccessContext context,
            String id, String salesMembershipId, long version) { throw unavailable(); }

    private static TenantBusinessDatabaseUnavailableException unavailable() {
        return new TenantBusinessDatabaseUnavailableException("Cloud delivery enables only the Buyer self-account read");
    }
}
