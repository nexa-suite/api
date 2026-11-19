package com.nexa.api.salescommitment.application.salesorder.port;

import org.springframework.modulith.NamedInterface;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundCandidateView;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;

@NamedInterface("sales-public")
public interface SalesOrderUseCase extends SalesOrderQueries {
	SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId, long purchaseRequestVersion,
			String idempotencyKey, String note);
	SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason, long expectedVersion);
	SalesOrderWalletRefundView refundBuyerWallet(CurrentAccessContext context, String id, BigDecimal amountPEN,
			String reason, String idempotencyKey);
	SalesPage<SalesOrderWalletRefundCandidateView> walletRefundCandidates(CurrentAccessContext context, int page,
			int size);
	default SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason,
			long expectedVersion, String idempotencyKey) {
		return transition(context, id, action, reason, expectedVersion);
	}
	SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext context, SalesOrderFilter filter);
}
