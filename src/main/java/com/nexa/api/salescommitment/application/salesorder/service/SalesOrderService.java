package com.nexa.api.salescommitment.application.salesorder.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.application.exception.SalesOrderRejectionReasonRequiredException;
import com.nexa.api.salescommitment.application.exception.SalesOrderTransitionException;
import com.nexa.api.salescommitment.application.model.SalesPage;
import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundView;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderWalletRefundCandidateView;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderApprovedWorkflowConversion;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFinancialStateQuery;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.salescommitment.domain.model.salesorder.SalesOrder;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands;
import com.nexa.api.payments.application.publicapi.BuyerWalletRefundBalanceQuery;
import com.nexa.api.payments.application.publicapi.BuyerWalletReservationCommands.RefundCommand;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class SalesOrderService implements SalesOrderUseCase, SalesOrderApprovedWorkflowConversion {
	private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");
	private final SalesOrderPersistencePort persistence;
	private final CustomerAccountQuery accounts;
	private final SalesOrderAggregatePersistencePort aggregatePersistence;
	private final SalesOrderConversionPersistencePort conversionPersistence;
	private final ConvertApprovedPurchaseRequestToSalesOrderService conversionService;
	private final IdempotencyPersistencePort idempotency;
	private final ObjectMapper objectMapper;
	private final SalesOrderFinancialStateQuery financialStates;
	private final BuyerWalletReservationCommands walletRefunds;
	private final BuyerWalletRefundBalanceQuery walletRefundBalances;

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts) {
		this(persistence, accounts, persistence instanceof SalesOrderAggregatePersistencePort aggregate ? aggregate : null,
				persistence instanceof SalesOrderConversionPersistencePort conversion ? conversion : null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence) {
		this(persistence, accounts, aggregatePersistence,
				persistence instanceof SalesOrderConversionPersistencePort conversion ? conversion : null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence) {
		this(persistence, accounts, aggregatePersistence, conversionPersistence, null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence,
			IdempotencyPersistencePort idempotency) {
		this(persistence, accounts, aggregatePersistence, conversionPersistence, idempotency, new ObjectMapper());
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence,
			IdempotencyPersistencePort idempotency, ObjectMapper objectMapper) {
		this(persistence, accounts, aggregatePersistence, conversionPersistence, idempotency, objectMapper, null, null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence,
			IdempotencyPersistencePort idempotency, ObjectMapper objectMapper,
			SalesOrderFinancialStateQuery financialStates, BuyerWalletReservationCommands walletRefunds) {
		this(persistence, accounts, aggregatePersistence, conversionPersistence, idempotency, objectMapper,
				financialStates, walletRefunds, null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, CustomerAccountQuery accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence,
			IdempotencyPersistencePort idempotency, ObjectMapper objectMapper,
			SalesOrderFinancialStateQuery financialStates, BuyerWalletReservationCommands walletRefunds,
			BuyerWalletRefundBalanceQuery walletRefundBalances) {
		this.persistence = Objects.requireNonNull(persistence, "Sales Order persistence is required");
		this.accounts = Objects.requireNonNull(accounts, "Client Account persistence is required");
		this.aggregatePersistence = Objects.requireNonNull(aggregatePersistence, "Sales Order aggregate persistence is required");
		this.conversionPersistence = Objects.requireNonNull(conversionPersistence, "Sales Order conversion persistence is required");
		this.conversionService = new ConvertApprovedPurchaseRequestToSalesOrderService(conversionPersistence);
		this.idempotency = idempotency;
		this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
		this.financialStates = financialStates;
		this.walletRefunds = walletRefunds;
		this.walletRefundBalances = walletRefundBalances;
	}

	@Override
	@Transactional(noRollbackFor = com.nexa.api.salescommitment.application.exception.PurchaseRequestExpiredException.class)
	public SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId, long purchaseRequestVersion,
			String idempotencyKey, String note) {
		return conversionService.convert(context, purchaseRequestId, purchaseRequestVersion, idempotencyKey, note);
	}

	@Override
	@Transactional(noRollbackFor = com.nexa.api.salescommitment.application.exception.PurchaseRequestExpiredException.class)
	public SalesOrderView convertApprovedBySystemWorkflow(CurrentAccessContext context, String purchaseRequestId,
			long purchaseRequestVersion, String idempotencyKey, String note) {
		return conversionService.convertApprovedBySystemWorkflow(context, purchaseRequestId, purchaseRequestVersion,
				idempotencyKey, note);
	}

	@Override
	public SalesPage<SalesOrderView> list(CurrentAccessContext context, SalesOrderFilter filter) {
		return persistence.list(scope(context), workspace(context), buyerAccount(context), filter);
	}

	@Override
	public SalesOrderView detail(CurrentAccessContext context, String id) {
		return persistence.find(scope(context), workspace(context), buyerAccount(context), id)
				.orElseThrow(() -> new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("sales-order"));
	}

	@Override
	@Transactional
	public SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason, long expectedVersion) {
		return transition(context, id, action, reason, expectedVersion, null);
	}

	@Override
	@Transactional
	public SalesOrderView transition(CurrentAccessContext context, String id, String action, String reason, long expectedVersion,
			String idempotencyKey) {
		commercialWrite(context);
		String normalized = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
		if (!(normalized.equals("confirm") || normalized.equals("reject") || normalized.equals("cancel"))) throw new SalesOrderTransitionException();
		if (normalized.equals("reject") && (reason == null || reason.isBlank())) throw new SalesOrderRejectionReasonRequiredException();
		String requestHash = null;
		if (idempotencyKey != null && !idempotencyKey.isBlank()) {
			requireIdempotencyKey(idempotencyKey);
			if (idempotency == null) throw new IllegalStateException("Sales Order idempotency is not configured");
			requestHash = transitionHash(id, normalized, reason, expectedVersion);
			idempotency.lock(scope(context), workspace(context), context.membershipId().toString(), "sales-order-transition", idempotencyKey);
			var prior = idempotency.find(scope(context), workspace(context), context.membershipId().toString(),
					"sales-order-transition", idempotencyKey, requestHash);
			if (prior.isPresent()) return replay(prior.get(), context);
		}
		SalesOrder aggregate = aggregatePersistence.findForUpdate(scope(context), workspace(context), id)
				.orElseThrow(() -> new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("sales-order"));
		if (aggregate.version() != expectedVersion) throw new com.nexa.api.salescommitment.application.exception.SalesConcurrencyConflictException();
		if ("confirm".equals(normalized) && aggregate.status() == com.nexa.api.salescommitment.domain.model.salesorder.SalesOrderStatus.CONFIRMED) {
			return persistence.find(scope(context), workspace(context), buyerAccount(context), id)
					.orElseThrow(() -> new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("sales-order"));
		}
		Instant at = java.time.Instant.ofEpochMilli(now());
		switch (normalized) {
			case "confirm" -> aggregate.confirm(at);
			case "reject" -> aggregate.reject(reason, at);
			case "cancel" -> aggregate.cancel(at);
		}
        SalesOrderView result = aggregatePersistence.saveTransition(aggregate, normalized, reason,
                context.membershipId().toString(), context.userId().toString(), expectedVersion,
                at.toEpochMilli(), context);
		if (requestHash != null) {
			idempotency.save(scope(context), workspace(context), context.membershipId().toString(), "sales-order-transition", idempotencyKey,
						result.id(), result.version(), java.util.UUID.randomUUID(), at.toEpochMilli(), requestHash, serialize(result));
		}
		return result;
	}

	@Override
	public SalesOrderWalletRefundView refundBuyerWallet(CurrentAccessContext context, String id, BigDecimal amountPEN,
			String reason, String idempotencyKey) {
		requireWalletRefundAuthority(context);
		if (financialStates == null || walletRefunds == null) {
			throw new com.nexa.api.payments.application.exception.BuyerWalletRefundUnavailableException();
		}
		if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.trim().length() > 160) {
			throw new IdempotencyKeyRequiredException();
		}
		String normalizedKey = idempotencyKey.trim();
		UUID orderId = UUID.fromString(Objects.requireNonNull(id, "Sales Order id is required"));
		String normalizedReason = reason == null ? null : reason.trim();
		Instant occurredAt = Instant.now();
		RefundCommand command = new RefundCommand(orderId, refundId(context, orderId, normalizedKey), amountPEN,
			normalizedReason, normalizedKey, occurredAt);
		SalesOrderFinancialStateQuery.State state = financialStates.findWalletRefundState(
				context.tenantId().value(), context.workspaceId().value(), orderId)
			.orElseThrow(() -> new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("sales-order"));
		if (!"CANCELLED".equals(state.status()) || !"WALLET".equals(state.paymentOption())) {
			throw new SalesOrderTransitionException();
		}
		BuyerWalletReservationCommands.Refund refund = walletRefunds.refundForSalesOrder(context, command);
		return new SalesOrderWalletRefundView(orderId, refund.refundId(), refund.ledgerEntryId(), refund.amountPEN(),
			"PEN", "POSTED");
	}

	@Override
	public com.nexa.api.salescommitment.application.model.SalesPage<SalesOrderWalletRefundCandidateView>
	walletRefundCandidates(CurrentAccessContext context, int page, int size) {
		requireWalletRefundAuthority(context);
		if (financialStates == null || walletRefundBalances == null) {
			throw new com.nexa.api.payments.application.exception.BuyerWalletRefundUnavailableException();
		}
		int normalizedPage = Math.max(0, page);
		int normalizedSize = Math.min(100, Math.max(1, size));
		SalesOrderFinancialStateQuery.CandidatePage candidatePage = financialStates.findCancelledWalletRefundCandidates(
				context.tenantId().value(), context.workspaceId().value(), normalizedPage, normalizedSize);
		List<UUID> salesOrderIds = candidatePage.items().stream()
				.map(SalesOrderFinancialStateQuery.Candidate::salesOrderId).toList();
		Map<UUID, BuyerWalletRefundBalanceQuery.RefundableBalance> balances =
				walletRefundBalances.findRefundableBalances(context, salesOrderIds);
		List<SalesOrderWalletRefundCandidateView> items = candidatePage.items().stream().map(candidate -> {
			var balance = balances.get(candidate.salesOrderId());
			return new SalesOrderWalletRefundCandidateView(candidate.salesOrderId(), candidate.orderNumber(), "PEN",
					balance == null ? ZERO_MONEY : balance.consumedPEN(),
					balance == null ? ZERO_MONEY : balance.refundedPEN(),
					balance == null ? ZERO_MONEY : balance.refundablePEN());
		}).toList();
		return new SalesPage<>(items, candidatePage.page(),
				candidatePage.size(), candidatePage.total());
	}

	@Override
	public List<SalesOrderEventView> events(CurrentAccessContext context, String id) {
		return persistence.events(scope(context), workspace(context), buyerAccount(context), id);
	}

	@Override
	public SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext context, SalesOrderFilter filter) {
		if (!context.allows(Permission.FULFILLMENT_READ) && !context.allows(Permission.LOGISTICS_READ)) throw new AccessPolicyViolation("Only warehouse or logistics can read fulfillment candidates");
		return persistence.fulfillmentCandidates(scope(context), workspace(context), filter);
	}

	private String buyerAccount(CurrentAccessContext context) {
		if (!context.hasRole(MembershipRole.BUYER)) {
			context.requirePermission(Permission.SALES_READ);
			return null;
		}
		return accounts.findBuyerReference(scope(context), workspace(context), context.membershipId().toString())
				.map(CustomerAccountReference::id).orElseThrow(() -> new com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException("client-account"));
	}

	private static void commercialWrite(CurrentAccessContext context) {
		context.requirePermission(Permission.SALES_WRITE);
	}
	private static void requireWalletRefundAuthority(CurrentAccessContext context) {
		Objects.requireNonNull(context, "Verified current access context is required").requireSurface(Surface.PLATFORM);
		if (!context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)
				&& !context.hasRole(MembershipRole.COMPANY_OWNER)) {
			throw new AccessPolicyViolation("A Business Operations Manager or Company Owner role is required");
		}
		context.requirePermission(PermissionKey.PAYMENT_RECONCILE);
	}
	private static void requireIdempotencyKey(String key) {
		if (key.length() > 160) throw new IdempotencyKeyRequiredException();
	}
	private static String transitionHash(String id, String action, String reason, long expectedVersion) {
		String canonical = id.trim() + "|" + action + "|" + (reason == null ? "<null>" : reason.trim()) + "|" + expectedVersion;
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is required", exception);
		}
	}
	private static UUID refundId(CurrentAccessContext context, UUID orderId, String idempotencyKey) {
		String canonical = "buyer-wallet-refund-v1|" + context.tenantId().value() + "|"
				+ context.workspaceId().value() + "|" + context.membershipId().value() + "|" + orderId + "|"
				+ idempotencyKey.trim();
		return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
	}
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
	private SalesOrderView replay(IdempotencyPersistencePort.IdempotencyResult prior, CurrentAccessContext context) {
		if (prior.responseJson() != null && !prior.responseJson().isBlank()) {
			try { return objectMapper.readValue(prior.responseJson(), SalesOrderView.class); }
			catch (Exception exception) { throw new IllegalStateException("Sales Order idempotency snapshot is invalid", exception); }
		}
		return detail(context, prior.resourceId());
	}
	private String serialize(SalesOrderView value) {
		try { return objectMapper.writeValueAsString(value); }
		catch (Exception exception) { throw new IllegalStateException("Sales Order idempotency snapshot could not be serialized", exception); }
	}
}
