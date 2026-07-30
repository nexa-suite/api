package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;

import java.util.List;
import java.util.Optional;

public interface SalesOrderPersistencePort {
	ConversionResult convertApproved(String tenantId, String workspaceId, String purchaseRequestId, long purchaseRequestVersion,
			String actorMembershipId, String idempotencyKey, String note, long nowEpochMillis);
	SalesPage<SalesOrderView> list(String tenantId, String workspaceId, String buyerAccountId, SalesOrderFilter filter);
	Optional<SalesOrderView> find(String tenantId, String workspaceId, String buyerAccountId, String id);
	SalesOrderView transition(String tenantId, String workspaceId, String id, String action, String reason,
			String actorMembershipId, long expectedVersion, long nowEpochMillis);
	List<SalesOrderEventView> events(String tenantId, String workspaceId, String buyerAccountId, String id);
	SalesPage<FulfillmentCandidateView> fulfillmentCandidates(String tenantId, String workspaceId, SalesOrderFilter filter);
	record ConversionResult(SalesOrderView order) { }
}
