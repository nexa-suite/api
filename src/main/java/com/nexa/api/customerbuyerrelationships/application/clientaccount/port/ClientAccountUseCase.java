package com.nexa.api.customerbuyerrelationships.application.clientaccount.port;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;

public interface ClientAccountUseCase {
	CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size);
	ClientAccountView detail(CurrentAccessContext context, String id);
	ClientAccountView buyerDetail(CurrentAccessContext context);
	List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context);
	ClientAccountView create(CurrentAccessContext context, ClientAccountView command);
	ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version);
	ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version);
	ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version);
}
