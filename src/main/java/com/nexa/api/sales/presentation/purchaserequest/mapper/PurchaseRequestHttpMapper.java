package com.nexa.api.sales.presentation.purchaserequest.mapper;

import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestEventView;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestDetailResponse;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestLineResponse;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestPageResponse;
import com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestSummaryResponse;
import com.nexa.api.sales.presentation.purchaserequest.request.CreatePurchaseRequestRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PurchaseRequestHttpMapper {
	public PurchaseRequestDetailResponse detail(PurchaseRequestView view) { return new PurchaseRequestDetailResponse(view.id(), view.code(), view.clientAccountId(), view.buyerMembershipId(), view.status(), view.priority(), view.requestedDeliveryDate(), view.deliveryProfileSnapshot(), view.paymentOption(), view.comment(), view.reviewNote(), view.lines().stream().map(this::line).toList(), view.version()); }
	public PurchaseRequestSummaryResponse summary(PurchaseRequestView view) { return new PurchaseRequestSummaryResponse(view.id(), view.code(), view.clientAccountId(), view.status(), view.priority(), view.requestedDeliveryDate(), view.lines().size(), view.version()); }
	public PurchaseRequestPageResponse page(SalesPage<PurchaseRequestView> page) { return new PurchaseRequestPageResponse(page.items().stream().map(this::summary).toList(), page.page(), page.size(), page.total()); }
	public com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestEventResponse event(PurchaseRequestEventView value) {
		return new com.nexa.api.sales.presentation.purchaserequest.response.PurchaseRequestEventResponse(value.id(), value.eventType(), value.fromStatus(), value.toStatus(), value.actorMembershipId(), value.occurredAt().toString());
	}
	public List<com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase.RequestedLine> requestedLines(CreatePurchaseRequestRequest request) {
		return request.lines().stream().map(line -> new com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase.RequestedLine(line.catalogItemId(), line.quantity(), line.unit(), line.notes())).toList();
	}
	private PurchaseRequestLineResponse line(PurchaseRequestLineView line) { return new PurchaseRequestLineResponse(line.id(), line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), line.version()); }
}
