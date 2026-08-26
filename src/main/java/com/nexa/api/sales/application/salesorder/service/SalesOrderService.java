package com.nexa.api.sales.application.salesorder.service;

import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.sales.application.exception.SalesOrderRejectionReasonRequiredException;
import com.nexa.api.sales.application.exception.SalesOrderTransitionException;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderUseCase;
import com.nexa.api.sales.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.sales.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.sales.application.exception.IdempotencyKeyRequiredException;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public class SalesOrderService implements SalesOrderUseCase {
	private final SalesOrderPersistencePort persistence;
	private final CustomerAccountQuery accounts;
	private final SalesOrderAggregatePersistencePort aggregatePersistence;
	private final SalesOrderConversionPersistencePort conversionPersistence;
	private final ConvertApprovedPurchaseRequestToSalesOrderService conversionService;
	private final IdempotencyPersistencePort idempotency;
	private final ObjectMapper objectMapper;

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
		this.persistence = Objects.requireNonNull(persistence, "Sales Order persistence is required");
		this.accounts = Objects.requireNonNull(accounts, "Client Account persistence is required");
		this.aggregatePersistence = Objects.requireNonNull(aggregatePersistence, "Sales Order aggregate persistence is required");
		this.conversionPersistence = Objects.requireNonNull(conversionPersistence, "Sales Order conversion persistence is required");
		this.conversionService = new ConvertApprovedPurchaseRequestToSalesOrderService(conversionPersistence);
		this.idempotency = idempotency;
		this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
	}

	@Override
	@Transactional(noRollbackFor = com.nexa.api.sales.application.exception.PurchaseRequestExpiredException.class)
	public SalesOrderView convert(CurrentAccessContext context, String purchaseRequestId, long purchaseRequestVersion,
			String idempotencyKey, String note) {
		return conversionService.convert(context, purchaseRequestId, purchaseRequestVersion, idempotencyKey, note);
	}

	@Override
	public SalesPage<SalesOrderView> list(CurrentAccessContext context, SalesOrderFilter filter) {
		return persistence.list(scope(context), workspace(context), buyerAccount(context), filter);
	}

	@Override
	public SalesOrderView detail(CurrentAccessContext context, String id) {
		return persistence.find(scope(context), workspace(context), buyerAccount(context), id)
				.orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("sales-order"));
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
				.orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("sales-order"));
		if (aggregate.version() != expectedVersion) throw new com.nexa.api.sales.application.exception.SalesConcurrencyConflictException();
		Instant at = java.time.Instant.ofEpochMilli(now());
		switch (normalized) {
			case "confirm" -> aggregate.confirm(at);
			case "reject" -> aggregate.reject(reason, at);
			case "cancel" -> aggregate.cancel(at);
		}
		SalesOrderView result = aggregatePersistence.saveTransition(aggregate, normalized, reason, context.membershipId().toString(), expectedVersion, at.toEpochMilli());
		if (requestHash != null) {
			idempotency.save(scope(context), workspace(context), context.membershipId().toString(), "sales-order-transition", idempotencyKey,
						result.id(), result.version(), java.util.UUID.randomUUID(), at.toEpochMilli(), requestHash, serialize(result));
		}
		return result;
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
				.map(CustomerAccountReference::id).orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account"));
	}

	private static void commercialWrite(CurrentAccessContext context) {
		context.requirePermission(Permission.SALES_WRITE);
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
