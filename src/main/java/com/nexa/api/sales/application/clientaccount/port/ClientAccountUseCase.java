package com.nexa.api.sales.application.clientaccount.port;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

public interface ClientAccountUseCase {
	SalesPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size);
	ClientAccountView detail(CurrentAccessContext context, String id);
	ClientAccountView create(CurrentAccessContext context, ClientAccountView command);
	ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version);
	ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version);
	ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version);
}
