package com.nexa.api.sales.application.service;

import com.nexa.api.sales.application.exception.*;
import com.nexa.api.sales.application.model.*;
import com.nexa.api.sales.application.port.in.SalesUseCase;
import com.nexa.api.sales.application.port.out.SalesPort;
import com.nexa.api.sales.domain.PurchaseRequestPriority;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

public class SalesService implements SalesUseCase {
	private final SalesPort port;
	public SalesService(SalesPort port) { this.port = port; }

	@Override public SalesPage<ClientAccountView> clientAccounts(CurrentAccessContext context, String search, String status, int page, int size) {
		internal(context, Permission.SALES_READ);
		return port.listClientAccounts(context.tenantId().toString(), context.workspaceId().toString(), search, status, page, size);
	}
	@Override public ClientAccountView clientAccount(CurrentAccessContext context, String id) {
		internal(context, Permission.SALES_READ);
		return port.findClientAccount(context.tenantId().toString(), context.workspaceId().toString(), id).orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
	}
	@Override @Transactional
	public ClientAccountView createClientAccount(CurrentAccessContext context, ClientAccountView command) {
		internal(context, Permission.SALES_WRITE);
		UUID id = UUID.randomUUID();
		port.insertClientAccount(command, context.tenantId().toString(), context.workspaceId().toString(), id, now());
		return clientAccount(context, id.toString());
	}
	@Override @Transactional
	public ClientAccountView updateClientAccount(CurrentAccessContext context, String id, ClientAccountView command, long version) {
		internal(context, Permission.SALES_WRITE);
		int changed = port.updateClientAccount(context.tenantId().toString(), context.workspaceId().toString(), id,
				command.businessName(), command.commercialName(), command.contactPerson(), command.contactEmail(), command.phone(),
				command.deliveryProfile(), command.paymentCondition(), version);
		if (changed == 0) throw new SalesConcurrencyConflictException();
		return clientAccount(context, id);
	}
	@Override @Transactional
	public ClientAccountView changeClientAccountStatus(CurrentAccessContext context, String id, String status, long version) {
		internal(context, Permission.SALES_WRITE);
		if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw new IllegalArgumentException("Unsupported client account status");
		if (port.updateClientAccountStatus(context.tenantId().toString(), context.workspaceId().toString(), id, status, version) == 0) throw new SalesConcurrencyConflictException();
		return clientAccount(context, id);
	}
	@Override @Transactional
	public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version) {
		internal(context, Permission.SALES_WRITE);
		ClientAccountView account = clientAccount(context, id);
		if (!port.isAvailableBuyerMembership(context.tenantId().toString(), context.workspaceId().toString(), membershipId)) throw new SalesResourceNotFoundException("buyer-membership");
		port.associateBuyer(context.tenantId().toString(), context.workspaceId().toString(), account.id(), membershipId, UUID.randomUUID(), now());
		return clientAccount(context, id);
	}

	@Override public SalesPage<PurchaseRequestView> purchaseRequests(CurrentAccessContext context, PurchaseRequestFilter filter) {
		String buyerAccount = buyerAccount(context);
		return port.listPurchaseRequests(context.tenantId().toString(), context.workspaceId().toString(), buyerAccount, filter);
	}
	@Override public PurchaseRequestView purchaseRequest(CurrentAccessContext context, String id) {
		return port.findPurchaseRequest(context.tenantId().toString(), context.workspaceId().toString(), buyerAccount(context), id)
				.orElseThrow(() -> new SalesResourceNotFoundException("purchase-request"));
	}
	@Override @Transactional
	public PurchaseRequestView createPurchaseRequest(CurrentAccessContext context, String clientAccountId, String priority,
			LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment, java.util.List<com.nexa.api.sales.application.port.in.SalesUseCase.RequestedLine> requestedLines) {
		String buyerAccount = buyerAccount(context);
		String effectiveAccount = buyerAccount != null ? buyerAccount : requiredClientAccount(context, clientAccountId);
		if (buyerAccount != null && clientAccountId != null && !clientAccountId.isBlank() && !buyerAccount.equals(clientAccountId)) throw new SalesResourceNotFoundException("client-account");
		UUID id = UUID.randomUUID();
		String code = "PR-" + id.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
		java.util.List<PurchaseRequestLineView> snapshots = new java.util.ArrayList<>();
		for (var requested : requestedLines == null ? java.util.List.<com.nexa.api.sales.application.port.in.SalesUseCase.RequestedLine>of() : requestedLines) {
			var snapshot = port.findActiveCatalogItem(requested.catalogItemId()).orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			if (requested.quantity() == null || requested.quantity().signum() <= 0) throw new IllegalArgumentException("Quantity must be greater than zero");
			snapshots.add(new PurchaseRequestLineView(UUID.randomUUID().toString(), snapshot.catalogItemId(), snapshot.itemName(), snapshot.presentation(), requested.quantity(), requested.unit() == null ? "unit" : requested.unit(), snapshot.price().amount(), snapshot.price().currency(), requested.notes(), 0));
		}
		if (snapshots.isEmpty()) throw new IllegalArgumentException("Purchase request requires a line");
		PurchaseRequestView draft = new PurchaseRequestView(id.toString(), code, effectiveAccount, context.membershipId().toString(),
				"DRAFT", priority == null ? "NORMAL" : priority, deliveryDate, deliveryProfile, paymentOption, comment, null, snapshots, 0);
		port.insertPurchaseRequest(draft, context.tenantId().toString(), context.workspaceId().toString(), id, now());
		for (var line : snapshots) port.insertLine(id.toString(), line, UUID.fromString(line.id()), now());
		return purchaseRequest(context, id.toString());
	}
	@Override @Transactional
	public PurchaseRequestView updatePurchaseRequest(CurrentAccessContext context, String id, String priority, LocalDate deliveryDate,
			String deliveryProfile, String paymentOption, String comment, long version) {
		canEdit(context, id);
		if (port.updatePurchaseRequest(context.tenantId().toString(), context.workspaceId().toString(), buyerAccount(context), id,
				priority, deliveryDate, deliveryProfile, paymentOption, comment, version) == 0) throw new SalesConcurrencyConflictException();
		return purchaseRequest(context, id);
	}
	@Override @Transactional
	public PurchaseRequestView addLine(CurrentAccessContext context, String id, String catalogItemId, BigDecimal quantity, String unit, String notes, long version) {
		canEdit(context, id);
		var snapshot = port.findActiveCatalogItem(catalogItemId).orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
		PurchaseRequestLineView line = new PurchaseRequestLineView(UUID.randomUUID().toString(), snapshot.catalogItemId(), snapshot.itemName(), snapshot.presentation(), quantity, unit == null ? "unit" : unit, snapshot.price().amount(), snapshot.price().currency(), notes, 0);
		if (port.updatePurchaseRequest(context.tenantId().toString(), context.workspaceId().toString(), buyerAccount(context), id, null, null, null, null, null, version) == 0) throw new SalesConcurrencyConflictException();
		port.insertLine(id, line, UUID.fromString(line.id()), now());
		return purchaseRequest(context, id);
	}
	@Override @Transactional
	public PurchaseRequestView updateLine(CurrentAccessContext context, String id, String lineId, BigDecimal quantity, String notes, long version) {
		canEdit(context, id);
		if (port.updateLine(id, lineId, quantity, notes, version) == 0) throw new SalesConcurrencyConflictException();
		return purchaseRequest(context, id);
	}
	@Override @Transactional
	public PurchaseRequestView deleteLine(CurrentAccessContext context, String id, String lineId, long version) {
		canEdit(context, id);
		if (port.deleteLine(id, lineId, version) == 0) throw new SalesConcurrencyConflictException();
		return purchaseRequest(context, id);
	}
	@Override @Transactional
	public PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote, long version, String idempotencyKey) {
		String normalized = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
		String from = purchaseRequest(context, id).status();
		String to = switch (normalized) {
			case "submit" -> "SUBMITTED";
			case "start-review" -> "IN_REVIEW";
			case "request-adjustment" -> "NEEDS_ADJUSTMENT";
			case "approve" -> "APPROVED";
			case "reject" -> "REJECTED";
			case "cancel" -> "CANCELLED";
			default -> throw new PurchaseRequestTransitionException();
		};
		if ("submit".equals(normalized)) {
			if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IdempotencyKeyRequiredException();
			var prior = port.findIdempotency(context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString(), "purchase-request-submission", idempotencyKey);
			if (prior.isPresent()) return purchaseRequest(context, prior.get().resourceId());
		}
		if ("submit".equals(normalized) || "cancel".equals(normalized)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
		if (port.transition(context.tenantId().toString(), context.workspaceId().toString(), buyerAccount(context), id, from, to,
				reviewNote, context.membershipId().toString(), version, UUID.randomUUID(), now()) == 0) throw new SalesConcurrencyConflictException();
		PurchaseRequestView result = purchaseRequest(context, id);
		if ("submit".equals(normalized)) port.saveIdempotency(context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString(), "purchase-request-submission", idempotencyKey, id, result.version(), UUID.randomUUID(), now());
		return result;
	}

	private String buyerAccount(CurrentAccessContext context) {
		if (context.role() != MembershipRole.BUYER) { internal(context, Permission.SALES_READ); return null; }
		return port.findClientAccountForBuyer(context.tenantId().toString(), context.workspaceId().toString(), context.membershipId().toString())
				.map(ClientAccountView::id).orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
	}
	private String requiredClientAccount(CurrentAccessContext context, String id) { internal(context, Permission.SALES_WRITE); if (id == null || id.isBlank()) throw new IllegalArgumentException("Client account is required"); return clientAccount(context, id).id(); }
	private void canEdit(CurrentAccessContext context, String id) { PurchaseRequestView request = purchaseRequest(context, id); boolean buyer = context.role() == MembershipRole.BUYER; if (buyer) buyerWrite(context); else internal(context, Permission.SALES_WRITE); if (!"DRAFT".equals(request.status()) && !(buyer && "NEEDS_ADJUSTMENT".equals(request.status()))) throw new PurchaseRequestTransitionException(); }
	private static void internal(CurrentAccessContext context, Permission permission) { if (context.role() == MembershipRole.BUYER) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Administrative sales access is not available to buyers"); context.requirePermission(permission); }
	private static void buyerWrite(CurrentAccessContext context) { context.requirePermission(Permission.SALES_BUYER_WRITE); }
	private static long now() { return Instant.now().toEpochMilli(); }
}
