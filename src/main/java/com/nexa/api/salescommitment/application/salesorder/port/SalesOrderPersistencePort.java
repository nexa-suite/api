package com.nexa.api.salescommitment.application.salesorder.port;

import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;

import java.util.List;
import java.util.Optional;

public interface SalesOrderPersistencePort {
	SalesPage<SalesOrderView> list(String tenantId, String workspaceId, String buyerAccountId, SalesOrderFilter filter);
	Optional<SalesOrderView> find(String tenantId, String workspaceId, String buyerAccountId, String id);
	List<SalesOrderEventView> events(String tenantId, String workspaceId, String buyerAccountId, String id);
	SalesPage<FulfillmentCandidateView> fulfillmentCandidates(String tenantId, String workspaceId, SalesOrderFilter filter);
}
