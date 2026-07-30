package com.nexa.api.sales.presentation.salesorder.mapper;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderLineView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.presentation.salesorder.response.FulfillmentCandidateResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderEventResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderLineResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderPageResponse;
import com.nexa.api.sales.presentation.salesorder.response.SalesOrderResponse;
import org.springframework.stereotype.Component;

public @Component class SalesOrderHttpMapper {
	public SalesOrderResponse response(SalesOrderView value) { return new SalesOrderResponse(value.id(), value.number(), value.tenantId(), value.workspaceId(), value.clientAccountId(), value.createdByMembershipId(), value.buyerMembershipId(), value.sourcePurchaseRequestId(), value.priority(), value.requestedDeliveryDate(), value.deliverySnapshot(), value.paymentOption(), value.notes(), value.currency(), value.total(), value.status(), value.createdAt(), value.updatedAt(), value.confirmedAt(), value.rejectedAt(), value.cancelledAt(), value.rejectionReason(), value.version(), value.lines().stream().map(this::line).toList()); }
	public SalesOrderPageResponse page(SalesPage<SalesOrderView> value) { return new SalesOrderPageResponse(value.items().stream().map(this::response).toList(), value.page(), value.size(), value.total()); }
	public SalesOrderEventResponse event(SalesOrderEventView value) { return new SalesOrderEventResponse(value.eventType(), value.fromStatus(), value.toStatus(), value.reason(), value.actorMembershipId(), value.occurredAt()); }
	public FulfillmentCandidateResponse candidate(FulfillmentCandidateView value) { return new FulfillmentCandidateResponse(value.id(), value.number(), value.clientAccountId(), value.status(), value.lines().stream().map(line -> new FulfillmentCandidateResponse.Line(line.catalogItemId(), line.itemName(), line.quantity(), line.unit())).toList()); }
	private SalesOrderLineResponse line(SalesOrderLineView line) { return new SalesOrderLineResponse(line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.lineSubtotal()); }
}
