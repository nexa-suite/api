package com.nexa.api.sales.application.salesorder.service;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
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
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import com.nexa.api.tenantmanagement.domain.model.membership.MembershipRole;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public class SalesOrderService implements SalesOrderUseCase {
	private final SalesOrderPersistencePort persistence;
	private final ClientAccountPersistencePort accounts;
	private final SalesOrderAggregatePersistencePort aggregatePersistence;
	private final SalesOrderConversionPersistencePort conversionPersistence;
	private final ConvertApprovedPurchaseRequestToSalesOrderService conversionService;

	public SalesOrderService(SalesOrderPersistencePort persistence, ClientAccountPersistencePort accounts) {
		this(persistence, accounts, persistence instanceof SalesOrderAggregatePersistencePort aggregate ? aggregate : null,
				persistence instanceof SalesOrderConversionPersistencePort conversion ? conversion : null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, ClientAccountPersistencePort accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence) {
		this(persistence, accounts, aggregatePersistence,
				persistence instanceof SalesOrderConversionPersistencePort conversion ? conversion : null);
	}

	public SalesOrderService(SalesOrderPersistencePort persistence, ClientAccountPersistencePort accounts,
			SalesOrderAggregatePersistencePort aggregatePersistence, SalesOrderConversionPersistencePort conversionPersistence) {
		this.persistence = persistence;
		this.accounts = accounts;
		this.aggregatePersistence = aggregatePersistence;
		this.conversionPersistence = conversionPersistence;
		this.conversionService = new ConvertApprovedPurchaseRequestToSalesOrderService(conversionPersistence);
	}

	@Override
	@Transactional
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
		commercialWrite(context);
		String normalized = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
		if (!(normalized.equals("confirm") || normalized.equals("reject") || normalized.equals("cancel"))) throw new SalesOrderTransitionException();
		if (normalized.equals("reject") && (reason == null || reason.isBlank())) throw new SalesOrderRejectionReasonRequiredException();
		if (aggregatePersistence != null) {
			SalesOrder aggregate = aggregatePersistence.findForUpdate(scope(context), workspace(context), id)
					.orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("sales-order"));
			if (aggregate.version() != expectedVersion) throw new com.nexa.api.sales.application.exception.SalesConcurrencyConflictException();
			Instant at = java.time.Instant.ofEpochMilli(now());
			switch (normalized) {
				case "confirm" -> aggregate.confirm(at);
				case "reject" -> aggregate.reject(reason, at);
				case "cancel" -> aggregate.cancel(at);
			}
			return aggregatePersistence.saveTransition(aggregate, normalized, reason, context.membershipId().toString(), expectedVersion, at.toEpochMilli());
		}
		return persistence.transition(scope(context), workspace(context), id, normalized, reason, context.membershipId().toString(), expectedVersion, now());
	}

	@Override
	public List<SalesOrderEventView> events(CurrentAccessContext context, String id) {
		return persistence.events(scope(context), workspace(context), buyerAccount(context), id);
	}

	@Override
	public SalesPage<FulfillmentCandidateView> fulfillmentCandidates(CurrentAccessContext context, SalesOrderFilter filter) {
		if (context.role() != MembershipRole.WAREHOUSE && context.role() != MembershipRole.LOGISTICS) throw new AccessPolicyViolation("Only warehouse or logistics can read fulfillment candidates");
		context.requirePermission(Permission.FULFILLMENT_READ);
		return persistence.fulfillmentCandidates(scope(context), workspace(context), filter);
	}

	private String buyerAccount(CurrentAccessContext context) {
		if (context.role() != MembershipRole.BUYER) {
			context.requirePermission(Permission.SALES_READ);
			return null;
		}
		return accounts.findForBuyer(scope(context), workspace(context), context.membershipId().toString())
				.map(ClientAccountView::id).orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("client-account"));
	}

	private static void commercialWrite(CurrentAccessContext context) {
		if (context.role() != MembershipRole.SALES && context.role() != MembershipRole.COMPANY_OWNER) throw new AccessPolicyViolation("Commercial sales access is required");
		context.requirePermission(Permission.SALES_WRITE);
	}
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
}
