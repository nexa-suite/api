package com.nexa.api.sales.application.salesorder.port;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;

public interface SalesOrderUseCase {
	SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId, long purchaseRequestVersion,
			String idempotencyKey, String note);
	SalesPage<SalesOrderView> list(CurrentAccessContext context, SalesOrderFilter filter);
	SalesOrderView detail(CurrentAccessContext context, String id);
	SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason, long expectedVersion);
	default SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason,
			long expectedVersion, String idempotencyKey) {
		return transition(context, id, action, reason, expectedVersion);
	}
	List<SalesOrderEventView> events(CurrentAccessContext context, String id);
	SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext context, SalesOrderFilter filter);
}
