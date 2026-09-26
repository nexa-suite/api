package com.nexa.api.salescommitment.application.purchaserequest.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.salescommitment.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.salescommitment.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.application.exception.PurchaseRequestExpiredException;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeProposalView;
import com.nexa.api.salescommitment.application.purchaserequest.model.MaterialChangeTerms;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.MaterialChangePersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestUseCase;
import com.nexa.api.salescommitment.domain.exception.SalesInvariantViolation;
import com.nexa.api.salescommitment.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.CatalogItemSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.DeliveryProfileSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PriceSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequest;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestLine;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestLineId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestStatus;
import com.nexa.api.salescommitment.domain.model.purchaserequest.RequestComment;
import com.nexa.api.salescommitment.domain.model.purchaserequest.RequestedDeliveryDate;
import com.nexa.api.salescommitment.domain.model.purchaserequest.RequestedQuantity;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.NoopChangeEventPersistence;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
	private final Clock clock;
	private final ObjectMapper objectMapper;
	private final MaterialChangePersistencePort materialChanges;

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
		this(persistence, events, idempotency, catalog, accounts, changeFeed, commitments, Clock.systemUTC());
	}

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
			ChangeEventPersistencePort changeFeed, CommercialCommitmentPort commitments, Clock clock) {
		this(persistence, events, idempotency, catalog, accounts, changeFeed, commitments, clock, new ObjectMapper());
	}

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
			ChangeEventPersistencePort changeFeed, CommercialCommitmentPort commitments, Clock clock, ObjectMapper objectMapper) {
		this(persistence, events, idempotency, catalog, accounts, changeFeed, commitments, clock, objectMapper, null);
	}

	public PurchaseRequestService(PurchaseRequestPersistencePort persistence, PurchaseRequestEventPersistencePort events,
			IdempotencyPersistencePort idempotency, CatalogItemSnapshotLookupPort catalog, CustomerAccountQuery accounts,
			ChangeEventPersistencePort changeFeed, CommercialCommitmentPort commitments, Clock clock, ObjectMapper objectMapper,
			MaterialChangePersistencePort materialChanges) {
		this.persistence = persistence;
		this.events = events;
		this.idempotency = idempotency;
		this.catalog = catalog;
		this.accounts = accounts;
		this.changeFeed = changeFeed;
		this.commitments = commitments;
		this.clock = clock == null ? Clock.systemUTC() : clock;
		this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
		this.materialChanges = materialChanges;
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
	public List<com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestEventView> events(CurrentAccessContext context, String id) {
		return persistence.events(scope(context), workspace(context), buyerAccount(context), id);
	}

	@Override
	public MaterialChangeProposalView currentMaterialChange(CurrentAccessContext context, String id) {
		PurchaseRequestView request = detail(context, id);
		if (!"CHANGES_PROPOSED".equals(request.status())) throw new SalesResourceNotFoundException("material-change");
		return materialChanges().findCurrent(scope(context), workspace(context), buyerAccount(context), id)
				.orElseThrow(() -> new SalesResourceNotFoundException("material-change"));
	}

	@Override
	public List<MaterialChangeProposalView> materialChangeHistory(CurrentAccessContext context, String id) {
		detail(context, id);
		return materialChanges().history(scope(context), workspace(context), buyerAccount(context), id);
	}

	@Override
	@Transactional
	public MaterialChangeProposalView proposeMaterialChange(CurrentAccessContext context, String id, long version,
			String reason, String priority, LocalDate deliveryDate, String deliveryProfile, String paymentOption,
			String comment, List<RequestedLine> requestedLines, String idempotencyKey) {
		internal(context, Permission.SALES_WRITE);
		requireIdempotencyKey(idempotencyKey);
		PurchaseRequestView current = detail(context, id);
		String actor = context.membershipId().toString();
		String operation = "purchase-request-material-change-proposal";
		String commandHash = materialChangeProposalHash(id, version, reason, priority, deliveryDate,
				deliveryProfile, paymentOption, comment, requestedLines);
		idempotency.lock(scope(context), workspace(context), actor, operation, idempotencyKey);
		var prior = idempotency.find(scope(context), workspace(context), actor, operation, idempotencyKey, commandHash);
		if (prior.isPresent()) return replayMaterialChange(prior.get());
		if (current.version() != version) throw new SalesConcurrencyConflictException();
		materializeExpiryIfDue(context, current, "material-change-proposal", version);
		if (!"SUBMITTED".equals(current.status())) throw new PurchaseRequestTransitionException();
		PurchaseRequest aggregate = rehydrate(current);
		aggregate.proposeChanges(reason);

		MaterialChangeTerms original = termsFromCurrent(current, true);
		MaterialChangeTerms proposed = proposeTerms(context, current, priority, deliveryDate, deliveryProfile,
				paymentOption, comment, requestedLines);
		if (sameTerms(original, proposed)) throw new SalesInvariantViolation("Material change proposal must change request terms");
		MaterialChangeProposalView result = materialChanges().propose(scope(context), workspace(context), id,
				version, actor, reason, original, proposed, now());
		events.append(UUID.randomUUID(), id, scope(context), workspace(context), actor,
				"MATERIAL_CHANGE_PROPOSED", current.status(), "CHANGES_PROPOSED", now());
		appendChange(context, persistence.find(scope(context), workspace(context), buyerAccount(context), id).orElseThrow(),
				"sales.purchase-request.material-change-proposed", "CHANGES_PROPOSED");
		idempotency.save(scope(context), workspace(context), actor, operation, idempotencyKey, id,
				result.requestVersion(), UUID.randomUUID(), now(), commandHash, serialize(result));
		return result;
	}

	@Override
	@Transactional(noRollbackFor = PurchaseRequestExpiredException.class)
	public PurchaseRequestView acceptMaterialChange(CurrentAccessContext context, String id, String proposalId,
			long version, String idempotencyKey) {
		buyerWrite(context);
		requireIdempotencyKey(idempotencyKey);
		PurchaseRequestView current = detail(context, id);
		String actor = context.membershipId().toString();
		String operation = "purchase-request-material-change-acceptance";
		String commandHash = materialChangeDecisionHash(id, proposalId, version);
		idempotency.lock(scope(context), workspace(context), actor, operation, idempotencyKey);
		var prior = idempotency.find(scope(context), workspace(context), actor, operation, idempotencyKey, commandHash);
		if (prior.isPresent()) return replay(prior.get(), context);
		if (current.version() != version || !"CHANGES_PROPOSED".equals(current.status())) throw new SalesConcurrencyConflictException();
		materializeExpiryIfDue(context, current, "material-change-acceptance", version);
		MaterialChangeProposalView proposal = materialChanges().findCurrent(scope(context), workspace(context),
				buyerAccount(context), id).filter(value -> value.id().equals(proposalId))
				.orElseThrow(() -> new SalesConcurrencyConflictException());
		if (proposal.requestVersion() != version) throw new SalesConcurrencyConflictException();
		PurchaseRequest aggregate = rehydrate(current);
		aggregate.acceptProposedChanges();
			MaterialChangeTerms accepted = revalidateProposal(context, current.clientAccountId(), proposal.proposedTerms());
		PurchaseRequestView result = materialChanges().accept(scope(context), workspace(context), buyerAccount(context),
				id, proposalId, version, actor, accepted, now());
		if (commitments != null) {
			commitments.releaseForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)),
					UUID.fromString(id), "MATERIAL_CHANGE_REPLACED");
			commitments.activateForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)),
					UUID.fromString(id));
		}
		events.append(UUID.randomUUID(), id, scope(context), workspace(context), actor,
				"MATERIAL_CHANGE_ACCEPTED", current.status(), "SUBMITTED", now());
		appendChange(context, result, "sales.purchase-request.material-change-accepted", "SUBMITTED");
		idempotency.save(scope(context), workspace(context), actor, operation, idempotencyKey, id,
				result.version(), UUID.randomUUID(), now(), commandHash, serialize(result));
		return result;
	}

	@Override
	@Transactional(noRollbackFor = PurchaseRequestExpiredException.class)
	public PurchaseRequestView rejectMaterialChange(CurrentAccessContext context, String id, String proposalId,
			long version, String idempotencyKey) {
		buyerWrite(context);
		requireIdempotencyKey(idempotencyKey);
		PurchaseRequestView current = detail(context, id);
		String actor = context.membershipId().toString();
		String operation = "purchase-request-material-change-rejection";
		String commandHash = materialChangeDecisionHash(id, proposalId, version);
		idempotency.lock(scope(context), workspace(context), actor, operation, idempotencyKey);
		var prior = idempotency.find(scope(context), workspace(context), actor, operation, idempotencyKey, commandHash);
		if (prior.isPresent()) return replay(prior.get(), context);
		if (current.version() != version || !"CHANGES_PROPOSED".equals(current.status())) {
			throw new SalesConcurrencyConflictException();
		}
		materializeExpiryIfDue(context, current, "material-change-rejection", version);
		MaterialChangeProposalView proposal = materialChanges().findCurrent(scope(context), workspace(context),
				buyerAccount(context), id).filter(value -> value.id().equals(proposalId))
				.orElseThrow(SalesConcurrencyConflictException::new);
		if (proposal.requestVersion() != version) throw new SalesConcurrencyConflictException();
		PurchaseRequestView result = materialChanges().reject(scope(context), workspace(context), buyerAccount(context),
				id, proposalId, version, actor, now());
		events.append(UUID.randomUUID(), id, scope(context), workspace(context), actor,
				"MATERIAL_CHANGE_REJECTED", current.status(), "SUBMITTED", now());
		appendChange(context, result, "sales.purchase-request.material-change-rejected", "SUBMITTED");
		idempotency.save(scope(context), workspace(context), actor, operation, idempotencyKey, id,
				result.version(), UUID.randomUUID(), now(), commandHash, serialize(result));
		return result;
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
			CatalogItemSnapshot item = catalog.findActive(requested.catalogItemId(), context.tenantId().value(),
					context.workspaceId().value(), account, requested.quantity())
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
		CatalogItemSnapshot item = catalog.findActive(catalogItemId, context.tenantId().value(),
				context.workspaceId().value(), current.clientAccountId(), quantity)
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
	@Transactional(noRollbackFor = PurchaseRequestExpiredException.class)
	public PurchaseRequestView transition(CurrentAccessContext context, String id, String action, String reviewNote,
			long version, String idempotencyKey) {
		String normalized = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
		PurchaseRequestView current = detail(context, id);
		String idempotencyOperation = null;
		String commandHash = null;
		if ("submit".equals(normalized)) {
			if (context.hasRole(MembershipRole.BUYER)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
			requireIdempotencyKey(idempotencyKey);
			idempotencyOperation = "purchase-request-submission";
			commandHash = requestHash(current);
			idempotency.lock(scope(context), workspace(context), context.membershipId().toString(),
					idempotencyOperation, idempotencyKey);
			var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(),
					idempotencyOperation, idempotencyKey, commandHash);
			if (prior.isPresent()) return replay(prior.get(), context);
		} else if ("withdraw".equals(normalized)) {
			if (context.hasRole(MembershipRole.BUYER)) buyerWrite(context); else internal(context, Permission.SALES_WRITE);
		} else if ("start-review".equals(normalized) || "approve".equals(normalized)) {
			internal(context, Permission.SALES_WRITE);
		} else {
			internal(context, Permission.SALES_WRITE);
		}
		if (idempotencyOperation == null && idempotencyKey != null && !idempotencyKey.isBlank()) {
			requireIdempotencyKey(idempotencyKey);
			idempotencyOperation = "purchase-request-transition";
			commandHash = transitionHash(current, normalized, reviewNote);
			idempotency.lock(scope(context), workspace(context), context.membershipId().toString(), idempotencyOperation, idempotencyKey);
			var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(),
					idempotencyOperation, idempotencyKey, commandHash);
			if (prior.isPresent()) return replay(prior.get(), context);
		}
		materializeExpiryIfDue(context, current, normalized, version);
		if ("start-review".equals(normalized) || "approve".equals(normalized)) {
			if (current.version() != version) throw new SalesConcurrencyConflictException();
			if (!"SUBMITTED".equals(current.status()) && !"CHANGES_PROPOSED".equals(current.status())) {
				throw new PurchaseRequestTransitionException();
			}
			assertCurrentPrices(context, current);
			// Review and approval are workflow actions, not persisted Purchase Request states.
			return current;
		}
		if ("submit".equals(normalized)) assertCurrentPrices(context, current);
		PurchaseRequest aggregate = rehydrate(current);
		String target = switch (normalized) {
			case "submit" -> { aggregate.submit(); yield PurchaseRequestStatus.SUBMITTED.name(); }
			case "reject" -> { aggregate.reject(reviewNote); yield PurchaseRequestStatus.REJECTED.name(); }
			// Keep the published v0.17.1 command behavior available for existing consumers.
			case "cancel" -> { aggregate.cancel(); yield PurchaseRequestStatus.CANCELLED.name(); }
			case "withdraw" -> { aggregate.withdraw(); yield PurchaseRequestStatus.WITHDRAWN.name(); }
			default -> throw new PurchaseRequestTransitionException();
		};
		int changed = persistence.transition(scope(context), workspace(context), buyerAccount(context), id,
				current.status(), target, reviewNote, context.membershipId().toString(), version);
		if (changed == 0) throw new SalesConcurrencyConflictException();
		if ("submit".equals(normalized) && commitments != null) {
			commitments.activateForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)), UUID.fromString(id));
		} else if (("reject".equals(normalized) || "cancel".equals(normalized) || "withdraw".equals(normalized)
				|| "request-adjustment".equals(normalized)) && commitments != null) {
			commitments.releaseForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)), UUID.fromString(id), target);
		}
		PurchaseRequestView result = detail(context, id);
		events.append(UUID.randomUUID(), id, scope(context), workspace(context), context.membershipId().toString(),
				target, current.status(), target, now());
		if ("submit".equals(normalized)) {
			events.appendCanonical("PURCHASE_REQUEST_SUBMITTED", id, scope(context), workspace(context),
				"purchase-request-" + id, null, "v" + result.version(), java.util.Map.of("purchaseRequestId", UUID.fromString(id), "status", target), now());
		}
	appendChange(context, result, eventType(normalized), target);
	if (idempotencyOperation != null) {
			idempotency.save(scope(context), workspace(context), context.membershipId().toString(),
					idempotencyOperation, idempotencyKey, id, result.version(), UUID.randomUUID(), now(), commandHash,
					serialize(result));
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

	private MaterialChangeTerms termsFromCurrent(PurchaseRequestView request, boolean preserveLineIds) {
		List<PurchaseRequestLineView> lines = request.lines().stream().map(line -> new PurchaseRequestLineView(
				preserveLineIds ? line.id() : UUID.randomUUID().toString(), line.catalogItemId(), line.itemName(),
				line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(),
				line.notes(), line.version())).toList();
		return new MaterialChangeTerms(request.priority(), request.requestedDeliveryDate(),
				request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), lines);
	}

	private MaterialChangeTerms proposeTerms(CurrentAccessContext context, PurchaseRequestView current,
			String priority, LocalDate deliveryDate, String deliveryProfile, String paymentOption,
			String comment, List<RequestedLine> requestedLines) {
		String normalizedPriority = priority == null ? current.priority() : PurchaseRequestPriority.from(priority).name();
		LocalDate normalizedDate = deliveryDate == null ? current.requestedDeliveryDate() : new RequestedDeliveryDate(deliveryDate).value();
		String normalizedDelivery = deliveryProfile == null ? current.deliveryProfileSnapshot()
				: new DeliveryProfileSnapshot(deliveryProfile).value();
		String normalizedPayment = paymentOption == null ? current.paymentOption()
				: java.util.Objects.requireNonNull(PaymentOption.from(paymentOption), "Payment option is required").name();
		String normalizedComment = comment == null ? current.comment() : new RequestComment(comment).value();
		List<PurchaseRequestLineView> lines;
		if (requestedLines == null) {
			lines = current.lines().stream().map(line -> new PurchaseRequestLineView(UUID.randomUUID().toString(),
					line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(),
					line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), 0)).toList();
		} else {
			if (requestedLines.isEmpty()) throw new SalesInvariantViolation("Material change requires a Purchase Request line");
			List<PurchaseRequestLineView> snapshots = new ArrayList<>();
			java.util.Set<String> uniqueCatalogIds = new java.util.HashSet<>();
			for (RequestedLine requested : requestedLines) {
				if (requested == null || requested.catalogItemId() == null || requested.catalogItemId().isBlank()
						|| !uniqueCatalogIds.add(requested.catalogItemId().trim())) {
					throw new SalesInvariantViolation("Material change has invalid or duplicate catalog items");
				}
				CatalogItemSnapshot item = catalog.findActive(requested.catalogItemId().trim(), context.tenantId().value(),
						context.workspaceId().value(), current.clientAccountId(), requested.quantity())
						.orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			RequestedQuantity quantity = new RequestedQuantity(requested.quantity());
			String unit = requested.unit() == null || requested.unit().isBlank() ? "unit" : requested.unit().trim();
			snapshots.add(lineView(UUID.randomUUID(), item, quantity, unit, requested.notes()));
			}
			lines = List.copyOf(snapshots);
		}
		return new MaterialChangeTerms(normalizedPriority, normalizedDate, normalizedDelivery,
				normalizedPayment, normalizedComment, lines);
	}

	private MaterialChangeTerms revalidateProposal(CurrentAccessContext context, String customerAccountId,
			MaterialChangeTerms proposed) {
		List<PurchaseRequestLineView> lines = new ArrayList<>();
		for (PurchaseRequestLineView line : proposed.lines()) {
			CatalogItemSnapshot currentItem = catalog.findActive(line.catalogItemId(), context.tenantId().value(),
					context.workspaceId().value(), customerAccountId, line.quantity())
					.orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			if (currentItem.price().amount().compareTo(line.unitPriceAmount()) != 0
					|| !currentItem.price().currency().equalsIgnoreCase(line.unitPriceCurrency())) {
				throw new com.nexa.api.salescommitment.application.exception.CommercialBusinessException("COMMERCIAL_POLICY_CHANGED");
			}
			lines.add(new PurchaseRequestLineView(line.id(), currentItem.catalogItemId(), currentItem.itemName(),
					currentItem.presentation(), line.quantity(), line.unit(), currentItem.price().amount(),
					currentItem.price().currency(), line.notes(), line.version()));
		}
		return new MaterialChangeTerms(proposed.priority(), proposed.requestedDeliveryDate(),
				proposed.deliveryProfileSnapshot(), proposed.paymentOption(), proposed.comment(), lines);
	}

	private void assertCurrentPrices(CurrentAccessContext context, PurchaseRequestView request) {
		for (PurchaseRequestLineView line : request.lines()) {
			CatalogItemSnapshot resolved = catalog.findActive(line.catalogItemId(), context.tenantId().value(),
					context.workspaceId().value(), request.clientAccountId(), line.quantity())
					.orElseThrow(() -> new SalesResourceNotFoundException("catalog-item"));
			if (resolved.price().amount().compareTo(line.unitPriceAmount()) != 0
					|| !resolved.price().currency().equalsIgnoreCase(line.unitPriceCurrency())) {
				throw new com.nexa.api.salescommitment.application.exception.CommercialBusinessException("COMMERCIAL_POLICY_CHANGED");
			}
		}
	}

	private static boolean sameTerms(MaterialChangeTerms left, MaterialChangeTerms right) {
		if (!java.util.Objects.equals(left.priority(), right.priority())
				|| !java.util.Objects.equals(left.requestedDeliveryDate(), right.requestedDeliveryDate())
				|| !java.util.Objects.equals(left.deliveryProfileSnapshot(), right.deliveryProfileSnapshot())
				|| !java.util.Objects.equals(left.paymentOption(), right.paymentOption())
				|| left.lines().size() != right.lines().size()) return false;
		for (int index = 0; index < left.lines().size(); index++) {
			PurchaseRequestLineView first = left.lines().get(index);
			PurchaseRequestLineView second = right.lines().get(index);
			if (!first.catalogItemId().equals(second.catalogItemId())
					|| first.quantity().compareTo(second.quantity()) != 0
					|| !first.unit().equals(second.unit())
					|| first.unitPriceAmount().compareTo(second.unitPriceAmount()) != 0
					|| !first.unitPriceCurrency().equalsIgnoreCase(second.unitPriceCurrency())) return false;
		}
		return true;
	}

	private String materialChangeProposalHash(String id, long version, String reason, String priority,
			LocalDate deliveryDate, String deliveryProfile, String paymentOption, String comment,
			List<RequestedLine> lines) {
		String canonical = id + "|" + version + "|" + value(reason) + "|" + value(priority) + "|" + value(deliveryDate)
				+ "|" + value(deliveryProfile) + "|" + value(paymentOption) + "|" + value(comment) + "|"
				+ (lines == null ? "<current>" : lines.stream().map(line -> value(line.catalogItemId()) + ":"
						+ value(line.quantity()) + ":" + value(line.unit()) + ":" + value(line.notes())
					).collect(java.util.stream.Collectors.joining(",")));
		return sha256(canonical);
	}

	private static String materialChangeDecisionHash(String id, String proposalId, long version) {
		return sha256(id + "|" + proposalId + "|" + version);
	}

	private static String sha256(String value) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
		catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is required", exception); }
	}

	private MaterialChangePersistencePort materialChanges() {
		if (materialChanges == null) throw new IllegalStateException("Purchase Request material-change persistence is not configured");
		return materialChanges;
	}

	private MaterialChangeProposalView replayMaterialChange(IdempotencyPersistencePort.IdempotencyResult prior) {
		if (prior.responseJson() == null || prior.responseJson().isBlank()) throw new IllegalStateException("Material change idempotency snapshot is missing");
		try { return objectMapper.readValue(prior.responseJson(), MaterialChangeProposalView.class); }
		catch (Exception exception) { throw new IllegalStateException("Material change idempotency snapshot is invalid", exception); }
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
			case "reject" -> "sales.purchase-request.rejected";
			case "withdraw" -> "sales.purchase-request.withdrawn";
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
	private long now() { return clock.millis(); }

	private void materializeExpiryIfDue(CurrentAccessContext context, PurchaseRequestView current, String action, long version) {
		if (current.expiresAt() == null || current.status().equals("DRAFT") || current.status().equals("EXPIRED")
				|| current.status().equals("REJECTED") || current.status().equals("CANCELLED")
				|| current.status().equals("WITHDRAWN") || current.status().equals("CONVERTED_TO_ORDER")) return;
		if (clock.instant().isBefore(current.expiresAt())) return;
		int changed = persistence.transition(scope(context), workspace(context), buyerAccount(context), current.id(),
				current.status(), PurchaseRequestStatus.EXPIRED.name(), "Business expiry", context.membershipId().toString(), version);
		if (changed == 1) {
			if (commitments != null) commitments.releaseForPurchaseRequest(UUID.fromString(scope(context)), UUID.fromString(workspace(context)), UUID.fromString(current.id()), "EXPIRED");
			events.append(UUID.randomUUID(), current.id(), scope(context), workspace(context), context.membershipId().toString(),
					"EXPIRED", current.status(), PurchaseRequestStatus.EXPIRED.name(), now());
		}
		throw new PurchaseRequestExpiredException();
	}

	private PurchaseRequestView replay(IdempotencyPersistencePort.IdempotencyResult prior, CurrentAccessContext context) {
		if (prior.responseJson() != null && !prior.responseJson().isBlank()) {
			try { return objectMapper.readValue(prior.responseJson(), PurchaseRequestView.class); }
			catch (Exception exception) { throw new IllegalStateException("Purchase Request idempotency snapshot is invalid", exception); }
		}
		return detail(context, prior.resourceId());
	}

	private String serialize(PurchaseRequestView value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (Exception exception) { throw new IllegalStateException("Purchase Request idempotency snapshot could not be serialized", exception); }
	}

	private String serialize(MaterialChangeProposalView value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (Exception exception) { throw new IllegalStateException("Material change idempotency snapshot could not be serialized", exception); }
	}

	private static String requestHash(PurchaseRequestView view) {
		String canonical = view.id() + "|"
				+ value(view.priority()) + "|" + value(view.requestedDeliveryDate()) + "|"
				+ value(view.deliveryProfileSnapshot()) + "|" + value(view.paymentOption()) + "|"
				+ value(view.comment()) + "|" + value(view.reviewNote()) + "|"
				+ view.lines().stream().map(line -> line.id() + ":" + line.catalogItemId() + ":" + line.quantity() + ":"
						+ line.unit() + ":" + value(line.unitPriceAmount()) + ":" + value(line.unitPriceCurrency()) + ":" + value(line.notes()))
				.collect(java.util.stream.Collectors.joining(","));
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static String transitionHash(PurchaseRequestView view, String action, String reviewNote) {
		String canonical = action + "|" + value(reviewNote) + "|" + requestHash(view);
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}

	private static String value(Object value) { return value == null ? "" : value.toString(); }
}
