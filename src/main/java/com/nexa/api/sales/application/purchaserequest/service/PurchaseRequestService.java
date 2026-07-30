package com.nexa.api.sales.application.purchaserequest.service;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.exception.*;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.purchaserequest.model.*;
import com.nexa.api.sales.application.purchaserequest.port.*;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.*;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PurchaseRequestService implements PurchaseRequestUseCase {
	private final PurchaseRequestPersistencePort persistence;
	private final PurchaseRequestEventPersistencePort events;
	private final IdempotencyPersistencePort idempotency;
	private final CatalogItemSnapshotLookupPort catalog;
	private final ClientAccountPersistencePort accounts;
	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events, IdempotencyPersistencePort idempotency,
			CatalogItemSnapshotLookupPort catalog, ClientAccountPersistencePort accounts) { this.persistence = persistence; this.events = events; this.idempotency = idempotency; this.catalog = catalog; this.accounts = accounts; }

	@Override public SalesPage<PurchaseRequestView> list(CurrentAccessContext context, PurchaseRequestFilter filter) { return persistence.list(scope(context), workspace(context), buyerAccount(context), filter); }
	@Override public PurchaseRequestView detail(CurrentAccessContext context, String id) { return persistence.find(scope(context), workspace(context), buyerAccount(context), id).orElseThrow(() -> new SalesResourceNotFoundException("purchase-request")); }
	@Override @Transactional public PurchaseRequestView create(CurrentAccessContext context, String clientAccountId, String priority, LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment, List<RequestedLine> requestedLines) {
		String buyer = buyerAccount(context); String account = buyer != null ? buyer : requiredClientAccount(context, clientAccountId);
		if (buyer != null && clientAccountId != null && !clientAccountId.isBlank() && !buyer.equals(clientAccountId)) throw new SalesResourceNotFoundException("client-account");
		UUID id = UUID.randomUUID(); String code = "PR-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
		PurchaseRequest aggregate = PurchaseRequest.draft(new PurchaseRequestId(id.toString()), account, new BuyerMembershipId(UUID.fromString(context.membershipId().toString())));
		if (deliveryDate != null) new RequestedDeliveryDate(deliveryDate); if (paymentOption != null) new PaymentOption(paymentOption); new RequestComment(comment); new DeliveryProfileSnapshot(deliveryProfile);
		List<PurchaseRequestLineView> snapshots = new ArrayList<>();
		for (RequestedLine requested : requestedLines == null ? List.<RequestedLine>of() : requestedLines) {
			CatalogItemSnapshot item = catalog.findActive(requested.catalogItemId()).orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			RequestedQuantity quantity = new RequestedQuantity(requested.quantity()); UUID lineId = UUID.randomUUID();
			aggregate.addLine(new PurchaseRequestLine(new PurchaseRequestLineId(lineId), item, quantity, requested.unit() == null ? "unit" : requested.unit(), requested.notes()));
			snapshots.add(new PurchaseRequestLineView(lineId.toString(), item.catalogItemId(), item.itemName(), item.presentation(), quantity.value(), requested.unit() == null ? "unit" : requested.unit(), item.price().amount(), item.price().currency(), requested.notes(), 0));
		}
		if (snapshots.isEmpty()) throw new SalesInvariantViolation("Purchase request requires a line");
		PurchaseRequestView draft = new PurchaseRequestView(id.toString(), code, account, context.membershipId().toString(), "DRAFT", priority == null ? "NORMAL" : priority, deliveryDate, deliveryProfile, paymentOption, comment, null, snapshots, 0);
		persistence.insert(draft, scope(context), workspace(context), id, now()); for (PurchaseRequestLineView line : snapshots) persistence.insertLine(id.toString(), line, UUID.fromString(line.id()), now()); return detail(context, id.toString());
	}
	@Override @Transactional public PurchaseRequestView update(CurrentAccessContext context, String id, String priority, LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment, long version) {
		canEdit(context, id); if (persistence.update(scope(context), workspace(context), buyerAccount(context), id, priority, deliveryDate, deliveryProfile, paymentOption, comment, version) == 0) throw new SalesConcurrencyConflictException(); return detail(context, id);
	}
	@Override @Transactional public PurchaseRequestView addLine(CurrentAccessContext context, String id, String catalogItemId, BigDecimal quantity, String unit, String notes, long version) {
		PurchaseRequestView current = canEdit(context, id); CatalogItemSnapshot item = catalog.findActive(catalogItemId).orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
		if (current.lines().stream().anyMatch(line -> line.catalogItemId().equals(catalogItemId))) throw new SalesInvariantViolation("Catalog item already exists in request");
		RequestedQuantity requestedQuantity = new RequestedQuantity(quantity); UUID lineId = UUID.randomUUID(); PurchaseRequestLineView line = new PurchaseRequestLineView(lineId.toString(), item.catalogItemId(), item.itemName(), item.presentation(), requestedQuantity.value(), unit == null ? "unit" : unit, item.price().amount(), item.price().currency(), notes, 0);
		if (persistence.update(scope(context), workspace(context), buyerAccount(context), id, null, null, null, null, null, version) == 0) throw new SalesConcurrencyConflictException(); persistence.insertLine(id, line, lineId, now()); return detail(context, id);
	}
	@Override @Transactional public PurchaseRequestView updateLine(CurrentAccessContext context, String id, String lineId, BigDecimal quantity, String notes, long version) { canEdit(context, id); new RequestedQuantity(quantity); if (persistence.updateLine(id, lineId, quantity, notes, version) == 0) throw new SalesConcurrencyConflictException(); return detail(context, id); }
	@Override @Transactional public PurchaseRequestView deleteLine(CurrentAccessContext context, String id, String lineId, long version) { canEdit(context, id); if (persistence.deleteLine(id, lineId, version) == 0) throw new SalesConcurrencyConflictException(); return detail(context, id); }
	@Override @Transactional public PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote, long version, String idempotencyKey) {
		String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT); String from = detail(context, id).status(); String to = switch (normalized) { case "submit" -> "SUBMITTED"; case "start-review" -> "IN_REVIEW"; case "request-adjustment" -> "NEEDS_ADJUSTMENT"; case "approve" -> "APPROVED"; case "reject" -> "REJECTED"; case "cancel" -> "CANCELLED"; default -> throw new PurchaseRequestTransitionException(); };
		if ("submit".equals(normalized)) { if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) throw new IdempotencyKeyRequiredException(); var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(), "purchase-request-submission", idempotencyKey); if (prior.isPresent()) return detail(context, prior.get().resourceId()); }
		if ("submit".equals(normalized) || "cancel".equals(normalized)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
		int changed = persistence.transition(scope(context), workspace(context), buyerAccount(context), id, from, to, reviewNote, context.membershipId().toString(), version);
		if (changed == 0) { if ("submit".equals(normalized)) { var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(), "purchase-request-submission", idempotencyKey); if (prior.isPresent()) return detail(context, prior.get().resourceId()); } throw new SalesConcurrencyConflictException(); }
		PurchaseRequestView result = detail(context, id); events.append(UUID.randomUUID(), id, scope(context), workspace(context), context.membershipId().toString(), to, from, to, now());
		if ("submit".equals(normalized)) idempotency.save(scope(context), workspace(context), context.membershipId().toString(), "purchase-request-submission", idempotencyKey, id, result.version(), UUID.randomUUID(), now()); return result;
	}

	private PurchaseRequestView canEdit(CurrentAccessContext context, String id) { PurchaseRequestView request = detail(context, id); if (context.role() == MembershipRole.BUYER) buyerWrite(context); else internal(context, Permission.SALES_WRITE); if (!"DRAFT".equals(request.status()) && !(context.role() == MembershipRole.BUYER && "NEEDS_ADJUSTMENT".equals(request.status()))) throw new PurchaseRequestTransitionException(); return request; }
	private String buyerAccount(CurrentAccessContext context) { if (context.role() != MembershipRole.BUYER) { internal(context, Permission.SALES_READ); return null; } return accounts.findForBuyer(scope(context), workspace(context), context.membershipId().toString()).map(ClientAccountView::id).orElseThrow(() -> new SalesResourceNotFoundException("client-account")); }
	private String requiredClientAccount(CurrentAccessContext context, String id) { internal(context, Permission.SALES_WRITE); if (id == null || id.isBlank()) throw new SalesInvariantViolation("Client account is required"); return accounts.find(scope(context), workspace(context), id).orElseThrow(() -> new SalesResourceNotFoundException("client-account")).id(); }
	private static void internal(CurrentAccessContext context, Permission permission) { if (context.role() == MembershipRole.BUYER) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Administrative sales access is not available to buyers"); context.requirePermission(permission); }
	private static void buyerWrite(CurrentAccessContext context) { context.requirePermission(Permission.SALES_BUYER_WRITE); }
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
}
