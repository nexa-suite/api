package com.nexa.api.sales.application.purchaserequest.service;

import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.sales.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.sales.application.port.CommercialCommitmentPort;
import com.nexa.api.sales.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.sales.domain.exception.SalesInvariantViolation;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.DeliveryProfileSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PriceSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequest;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestLine;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestLineId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestStatus;
import com.nexa.api.sales.domain.model.purchaserequest.RequestComment;
import com.nexa.api.sales.domain.model.purchaserequest.RequestedDeliveryDate;
import com.nexa.api.sales.domain.model.purchaserequest.RequestedQuantity;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.NoopChangeEventPersistence;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
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
	private final CustomerAccountQuery accounts;
	private final ChangeEventPersistencePort changeFeed;
	private final CommercialCommitmentPort commitments;

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts) {
		this(persistence, events, idempotency, catalog, accounts, new NoopChangeEventPersistence(), null);
	}

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
			ChangeEventPersistencePort changeFeed) {
		this(persistence, events, idempotency, catalog, accounts, changeFeed, null);
	}

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
			ChangeEventPersistencePort changeFeed, CommercialCommitmentPort commitments) {
		this.persistence = persistence;
		this.events = events;
		this.idempotency = idempotency;
		this.catalog = catalog;
		this.accounts = accounts;
		this.changeFeed = changeFeed;
		this.commitments = commitments;
	}

	@Override
	public SalesPage<PurchaseRequestView> list(CurrentAccessContext context, PurchaseRequestFilter filter) {
		return persistence.list(scope(context), workspace(context), buyerAccount(context), filter);
	}

	@Override
	public PurchaseRequestView detail(CurrentAccessContext context, String id) {
		return persistence.find(scope(context), workspace(context), buyerAccount(context), id)
				.orElseThrow(() -> new SalesResourceNotFoundException("purchase-request"));
	}

	@Override
	public List<com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestEventView> events(CurrentAccessContext context, String id) {
		return persistence.events(scope(context), workspace(context), buyerAccount(context), id);
	}

	@Override
	@Transactional
	public PurchaseRequestView create(CurrentAccessContext context, String requestedClientAccountId, String priority,
			LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment, List<RequestedLine> requestedLines) {
		String account;
		if (context.hasRole(MembershipRole.BUYER)) {
			buyerWrite(context);
			account = buyerAccount(context);
			if (requestedClientAccountId != null && !requestedClientAccountId.isBlank()
					&& !account.equals(requestedClientAccountId.trim())) {
				throw new SalesResourceNotFoundException("client-account");
			}
		} else {
			internal(context, Permission.SALES_WRITE);
			if (requestedClientAccountId == null || requestedClientAccountId.isBlank()) {
				throw new SalesInvariantViolation("Client Account is required for an internal Purchase Request");
			}
			account = accounts.findReference(scope(context), workspace(context), requestedClientAccountId.trim())
					.filter(value -> "ACTIVE".equals(value.status()))
					.map(CustomerAccountReference::id)
					.orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
		}
		PurchaseRequestPriority priorityValue = PurchaseRequestPriority.from(priority);
		PaymentOption paymentValue = PaymentOption.from(paymentOption);
		new RequestedDeliveryDate(deliveryDate);
		new DeliveryProfileSnapshot(deliveryProfile);
		new RequestComment(comment);

		UUID id = UUID.randomUUID();
		String code = "PR-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT);
		PurchaseRequest aggregate = PurchaseRequest.draft(new PurchaseRequestId(id.toString()), account,
				new BuyerMembershipId(UUID.fromString(context.membershipId().toString())));
		aggregate.updateDetails(priorityValue, new RequestedDeliveryDate(deliveryDate), new DeliveryProfileSnapshot(deliveryProfile), paymentValue, new RequestComment(comment));
		List<PurchaseRequestLineView> snapshots = new ArrayList<>();
		for (RequestedLine requested : requestedLines == null ? List.<RequestedLine>of() : requestedLines) {
			CatalogItemSnapshot item = catalog.findActive(requested.catalogItemId(), context.tenantId().value(), context.workspaceId().value())
					.orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			RequestedQuantity quantity = new RequestedQuantity(requested.quantity());
			UUID lineId = UUID.randomUUID();
			String unit = requested.unit() == null ? "unit" : requested.unit();
			aggregate.addLine(new PurchaseRequestLine(new PurchaseRequestLineId(lineId), item, quantity, unit, requested.notes()));
			snapshots.add(lineView(lineId, item, quantity, unit, requested.notes()));
		}
		persistence.insert(new PurchaseRequestView(id.toString(), code, account, context.membershipId().toString(),
				aggregate.status().name(), aggregate.priority().name(), deliveryDate, deliveryProfile,
				paymentValue == null ? null : paymentValue.name(), comment, null, snapshots, 0),
				scope(context), workspace(context), id, now());
		for (PurchaseRequestLineView line : snapshots) persistence.insertLine(id.toString(), line, UUID.fromString(line.id()), now());
		changeFeed.append(scope(context), workspace(context), account, "purchase_request", id.toString(),
				"sales.purchase-request.created", "DRAFT", now(), true);
		return detail(context, id.toString());
	}

	@Override
	@Transactional
	public PurchaseRequestView update(CurrentAccessContext context, String id, String priority, LocalDate deliveryDate,
			String deliveryProfile, String paymentOption, String comment, long version) {
		PurchaseRequestView current = canEdit(context, id);
		PurchaseRequest aggregate = rehydrate(current);
		aggregate.updateDetails(priority == null ? aggregate.priority() : PurchaseRequestPriority.from(priority),
				deliveryDate == null ? aggregate.requestedDeliveryDate() : new RequestedDeliveryDate(deliveryDate),
				deliveryProfile == null ? aggregate.deliveryProfile() : new DeliveryProfileSnapshot(deliveryProfile),
				paymentOption == null ? aggregate.paymentOption() : PaymentOption.from(paymentOption),
				comment == null ? aggregate.comment() : new RequestComment(comment));
		if (persistence.update(scope(context), workspace(context), buyerAccount(context), id,
				priority == null ? null : aggregate.priority().name(), deliveryDate, deliveryProfile,
				paymentOption == null ? null : aggregate.paymentOption().name(), comment, version) == 0) {
			throw new SalesConcurrencyConflictException();
		}
		PurchaseRequestView result = detail(context, id);
		appendChange(context, result, "sales.purchase-request.updated", null);
		return result;
	}

	@Override
	@Transactional
	public PurchaseRequestView addLine(CurrentAccessContext context, String id, String catalogItemId, BigDecimal quantity,
			String unit, String notes, long version) {
		PurchaseRequestView current = canEdit(context, id);
		CatalogItemSnapshot item = catalog.findActive(catalogItemId, context.tenantId().value(), context.workspaceId().value())
				.orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
		if (current.lines().stream().anyMatch(line -> line.catalogItemId().equals(catalogItemId))) {
			throw new SalesInvariantViolation("Catalog item already exists in request");
		}
		RequestedQuantity requestedQuantity = new RequestedQuantity(quantity);
		UUID lineId = UUID.randomUUID();
		String normalizedUnit = unit == null ? "unit" : unit;
		PurchaseRequestLineView line = lineView(lineId, item, requestedQuantity, normalizedUnit, notes);
		if (persistence.update(scope(context), workspace(context), buyerAccount(context), id,
				null, null, null, null, null, version) == 0) throw new SalesConcurrencyConflictException();
		persistence.insertLine(id, line, lineId, now());
		PurchaseRequestView result = detail(context, id);
		appendChange(context, result, "sales.purchase-request.updated", null);
		return result;
	}

	@Override
	@Transactional
	public PurchaseRequestView updateLine(CurrentAccessContext context, String id, String lineId, BigDecimal quantity,
			String notes, long version) {
		canEdit(context, id);
		new RequestedQuantity(quantity);
		if (persistence.updateLine(scope(context), workspace(context), buyerAccount(context), id, lineId, quantity, notes, version) == 0) {
			throw new SalesConcurrencyConflictException();
		}
		PurchaseRequestView result = detail(context, id);
		appendChange(context, result, "sales.purchase-request.updated", null);
		return result;
	}

	@Override
	@Transactional
	public PurchaseRequestView deleteLine(CurrentAccessContext context, String id, String lineId, long version) {
		canEdit(context, id);
		if (persistence.deleteLine(scope(context), workspace(context), buyerAccount(context), id, lineId, version) == 0) {
			throw new SalesConcurrencyConflictException();
		}
		PurchaseRequestView result = detail(context, id);
		appendChange(context, result, "sales.purchase-request.updated", null);
		return result;
	}

	@Override
	@Transactional
	public PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote,
			long version, String idempotencyKey) {
		String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		PurchaseRequestView current = detail(context, id);
		if ("submit".equals(normalized)) {
			if (context.hasRole(MembershipRole.BUYER)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
			requireIdempotencyKey(idempotencyKey);
			var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(),
					"purchase-request-submission", idempotencyKey);
			if (prior.isPresent()) return detail(context, prior.get().resourceId());
		} else {
			internal(context, Permission.SALES_WRITE);
		}
		PurchaseRequest aggregate = rehydrate(current);
		String target = switch (normalized) {
			case "submit" -> { aggregate.submit(); yield PurchaseRequestStatus.SUBMITTED.name(); }
			case "start-review" -> { aggregate.startReview(); yield PurchaseRequestStatus.IN_REVIEW.name(); }
			case "request-adjustment" -> { aggregate.requestAdjustment(reviewNote); yield PurchaseRequestStatus.NEEDS_ADJUSTMENT.name(); }
			case "approve" -> { aggregate.approve(reviewNote); yield PurchaseRequestStatus.APPROVED.name(); }
			case "reject" -> { aggregate.reject(reviewNote); yield PurchaseRequestStatus.REJECTED.name(); }
			case "cancel" -> { aggregate.cancel(); yield PurchaseRequestStatus.CANCELLED.name(); }
			default -> throw new PurchaseRequestTransitionException();
		};
		int changed = persistence.transition(scope(context), workspace(context), buyerAccount(context), id,
				current.status(), target, reviewNote, context.membershipId().toString(), version);
		if (changed == 0) throw new SalesConcurrencyConflictException();
		if ("submit".equals(normalized) && commitments != null) {
			commitments.activateForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)), UUID.fromString(id));
		} else if (("reject".equals(normalized) || "cancel".equals(normalized)) && commitments != null) {
			commitments.releaseForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)), UUID.fromString(id), target);
		}
		PurchaseRequestView result = detail(context, id);
		events.append(UUID.randomUUID(), id, scope(context), workspace(context), context.membershipId().toString(),
				target, current.status(), target, now());
		if ("submit".equals(normalized)) {
			events.appendCanonical("PURCHASE_REQUEST_SUBMITTED", id, scope(context), workspace(context),
				"purchase-request-" + id, null, java.util.Map.of("purchaseRequestId", UUID.fromString(id), "status", target), now());
		} else if ("approve".equals(normalized)) {
			events.appendCanonical("PURCHASE_REQUEST_APPROVED", id, scope(context), workspace(context),
				"purchase-request-" + id, null, java.util.Map.of("purchaseRequestId", UUID.fromString(id), "purchaseRequestVersion", result.version()), now());
		}
		appendChange(context, result, eventType(normalized), target);
		if ("submit".equals(normalized)) {
			idempotency.save(scope(context), workspace(context), context.membershipId().toString(),
					"purchase-request-submission", idempotencyKey, id, result.version(), UUID.randomUUID(), now());
		}
		return result;
	}

	private PurchaseRequestView canEdit(CurrentAccessContext context, String id) {
		PurchaseRequestView request = detail(context, id);
		if (context.hasRole(MembershipRole.BUYER)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
		if (!"DRAFT".equals(request.status()) && !(context.hasRole(MembershipRole.BUYER) && "NEEDS_ADJUSTMENT".equals(request.status()))) {
			throw new PurchaseRequestTransitionException();
		}
		return request;
	}

	private String buyerAccount(CurrentAccessContext context) {
		if (!context.hasRole(MembershipRole.BUYER)) {
			internal(context, Permission.SALES_READ);
			return null;
		}
		return accounts.findBuyerReference(scope(context), workspace(context), context.membershipId().toString())
				.map(CustomerAccountReference::id).orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
	}

	private static void buyerWrite(CurrentAccessContext context) {
		if (!context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Purchase request creation is buyer-only");
		context.requirePermission(Permission.SALES_BUYER_WRITE);
	}

	private static void internal(CurrentAccessContext context, Permission permission) {
		if (context.hasRole(MembershipRole.BUYER)) throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
		context.requirePermission(permission);
	}

	private PurchaseRequest rehydrate(PurchaseRequestView view) {
		List<PurchaseRequestLine> lines = view.lines().stream().map(line -> new PurchaseRequestLine(
				new PurchaseRequestLineId(UUID.fromString(line.id())),
				new CatalogItemSnapshot(line.catalogItemId(), line.itemName(), line.presentation(),
						new PriceSnapshot(line.unitPriceAmount(), line.unitPriceCurrency())),
				new RequestedQuantity(line.quantity()), line.unit(), line.notes())).toList();
		return PurchaseRequest.rehydrate(new PurchaseRequestId(view.id()), view.clientAccountId(),
				new BuyerMembershipId(UUID.fromString(view.buyerMembershipId())),
				PurchaseRequestStatus.valueOf(view.status()), PurchaseRequestPriority.from(view.priority()),
				view.requestedDeliveryDate() == null ? null : new RequestedDeliveryDate(view.requestedDeliveryDate()),
				new DeliveryProfileSnapshot(view.deliveryProfileSnapshot()), PaymentOption.from(view.paymentOption()),
				new RequestComment(view.comment()), view.reviewNote(), lines);
	}

	private void appendChange(CurrentAccessContext context, PurchaseRequestView view, String eventType, String publicStatus) {
		changeFeed.append(scope(context), workspace(context), view.clientAccountId(), "purchase_request", view.id(), eventType, publicStatus, now(), view.clientAccountId() != null);
	}

	private static String eventType(String action) {
		return switch (action) {
			case "submit" -> "sales.purchase-request.submitted";
			case "start-review" -> "sales.purchase-request.review-started";
			case "request-adjustment" -> "sales.purchase-request.adjustment-requested";
			case "approve" -> "sales.purchase-request.approved";
			case "reject" -> "sales.purchase-request.rejected";
			case "cancel" -> "sales.purchase-request.cancelled";
			default -> "sales.purchase-request.updated";
		};
	}

	private static PurchaseRequestLineView lineView(UUID id, CatalogItemSnapshot item, RequestedQuantity quantity, String unit, String notes) {
		return new PurchaseRequestLineView(id.toString(), item.catalogItemId(), item.itemName(), item.presentation(),
				quantity.value(), unit, item.price().amount(), item.price().currency(), notes, 0);
	}

	private static void requireIdempotencyKey(String key) {
		if (key == null || key.isBlank() || key.length() > 160) throw new IdempotencyKeyRequiredException();
	}
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
}
