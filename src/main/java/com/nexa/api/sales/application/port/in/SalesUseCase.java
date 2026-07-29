package com.nexa.api.sales.application.port.in;

import com.nexa.api.sales.application.model.*;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface SalesUseCase {
	SalesPage<ClientAccountView> clientAccounts(CurrentAccessContext context, String search, String status, int page, int size);
	ClientAccountView clientAccount(CurrentAccessContext context, String id);
	ClientAccountView createClientAccount(CurrentAccessContext context, ClientAccountView command);
	ClientAccountView updateClientAccount(CurrentAccessContext context, String id, ClientAccountView command, long version);
	ClientAccountView changeClientAccountStatus(CurrentAccessContext context, String id, String status, long version);
	ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version);
	SalesPage<PurchaseRequestView> purchaseRequests(CurrentAccessContext context, PurchaseRequestFilter filter);
	PurchaseRequestView purchaseRequest(CurrentAccessContext context, String id);
	PurchaseRequestView createPurchaseRequest(CurrentAccessContext context, String clientAccountId, String priority,
			LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment, List<RequestedLine> lines);
	PurchaseRequestView updatePurchaseRequest(CurrentAccessContext context, String id, String priority, LocalDate deliveryDate,
			String deliveryProfile, String paymentOption, String comment, long version);
	PurchaseRequestView addLine(CurrentAccessContext context, String id, String catalogItemId, BigDecimal quantity, String unit, String notes, long version);
	PurchaseRequestView updateLine(CurrentAccessContext context, String id, String lineId, BigDecimal quantity, String notes, long version);
	PurchaseRequestView deleteLine(CurrentAccessContext context, String id, String lineId, long version);
	PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote, long version, String idempotencyKey);
	record RequestedLine(String catalogItemId, BigDecimal quantity, String unit, String notes) { }
}
