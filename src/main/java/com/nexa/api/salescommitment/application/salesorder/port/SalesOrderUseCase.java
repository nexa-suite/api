package com.nexa.api.salescommitment.application.salesorder.port;

import org.springframework.modulith.NamedInterface;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundCandidateView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.List;
import com.nexa.api.salescommitment.application.model.SalesPage;

@NamedInterface("sales-public")
public interface SalesOrderUseCase {
	SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId, long purchaseRequestVersion,
			String idempotencyKey, String note);
	SalesPage<SalesOrderView> list(CurrentAccessContext context, SalesOrderFilter filter);
	SalesOrderView detail(CurrentAccessContext context, String id);
	SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason, long expectedVersion);
	SalesOrderWalletRefundView refundBuyerWallet(CurrentAccessContext context, String id, BigDecimal amountPEN,
			String reason, String idempotencyKey);
	SalesPage<SalesOrderWalletRefundCandidateView> walletRefundCandidates(CurrentAccessContext context, int page,
			int size);
	default SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason,
			long expectedVersion, String idempotencyKey) {
		return transition(context, id, action, reason, expectedVersion);
	}
	List<SalesOrderEventView> events(CurrentAccessContext context, String id);
	SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext context, SalesOrderFilter filter);
}
