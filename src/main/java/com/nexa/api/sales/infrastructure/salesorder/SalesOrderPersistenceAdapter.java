package com.nexa.api.sales.infrastructure.salesorder;

import com.nexa.api.sales.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.exception.SalesIdempotencyPayloadConflictException;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.model.SalesOrderLineView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderAggregatePersistencePort;
import com.nexa.api.sales.application.salesorder.port.SalesOrderConversionPersistencePort;
import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.salesorder.ApprovedPurchaseRequestSnapshot;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderLine;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderNumber;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.tenantmanagement.domain.model.identity.TenantId;
import com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class SalesOrderPersistenceAdapter implements SalesOrderPersistencePort, SalesOrderAggregatePersistencePort, SalesOrderConversionPersistencePort {
	private final JdbcTemplate jdbc;
	private final ChangeEventPersistencePort changeFeed;

	public SalesOrderPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed) {
		this.jdbc = jdbc;
		this.changeFeed = changeFeed;
	}

	@Override
	public Optional<SalesOrder> findForUpdate(String tenantId, String workspaceId, String salesOrderId) {
		List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId), uuid(salesOrderId)));
		return jdbc.query(orderSql() + " where o.tenant_id=? and o.workspace_id=? and o.id=? for update of o", rs -> rs.next() ? Optional.of(aggregate(detail(rs))) : Optional.empty(), args.toArray());
	}

	@Override
	public SalesOrderView saveTransition(SalesOrder aggregate, String action, String reason, String actorMembershipId,
			long expectedVersion, long nowEpochMillis) {
		if (aggregate.version() != expectedVersion) throw new SalesConcurrencyConflictException();
		UUID orderId = uuid(aggregate.id().value()), tenant = aggregate.tenantId().value(), workspace = aggregate.workspaceId().value(), actor = uuid(actorMembershipId);
		int changed = jdbc.update("update sales.sales_order set status=?,rejection_reason=?,confirmed_at=?,rejected_at=?,cancelled_at=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='PENDING' and version=?",
				aggregate.status().name(), aggregate.rejectionReason(), aggregate.confirmedAt() == null ? null : timestamp(aggregate.confirmedAt()), aggregate.rejectedAt() == null ? null : timestamp(aggregate.rejectedAt()), aggregate.cancelledAt() == null ? null : timestamp(aggregate.cancelledAt()), timestamp(nowEpochMillis), tenant, workspace, orderId, expectedVersion);
		if (changed != 1) throw new SalesConcurrencyConflictException();
		jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,reason,occurred_at) values (?,?,?,?,?,'ORDER_STATUS_CHANGED','PENDING',?,?,?)",
				UUID.randomUUID(), orderId, tenant, workspace, actor, aggregate.status().name(), reason, timestamp(nowEpochMillis));
		String eventType = switch (action) {
			case "confirm" -> "sales.sales-order.confirmed";
			case "reject" -> "sales.sales-order.rejected";
			case "cancel" -> "sales.sales-order.cancelled";
			default -> throw new com.nexa.api.sales.application.exception.SalesOrderTransitionException();
		};
		changeFeed.append(tenant.toString(), workspace.toString(), aggregate.clientAccountId().toString(), "sales_order", orderId.toString(), eventType, aggregate.status().name(), nowEpochMillis, true);
		return find(tenant.toString(), workspace.toString(), null, orderId.toString()).orElseThrow();
	}

	@Override
	public Optional<SalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId, String idempotencyKey) {
		lockConversionIdempotency(tenantId, workspaceId, actorMembershipId, idempotencyKey);
		return findIdempotent(tenantId, workspaceId, actorMembershipId, idempotencyKey);
	}

	@Override
	public Optional<SalesOrderView> findByIdempotency(String tenantId, String workspaceId, String actorMembershipId,
			String idempotencyKey, String requestHash) {
		lockConversionIdempotency(tenantId, workspaceId, actorMembershipId, idempotencyKey);
		return jdbc.query("select resource_id,request_hash from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='purchase-request-order-conversion' and idempotency_key=?",
			rs -> {
				if (!rs.next()) return Optional.empty();
				String stored = rs.getString(2);
				if (stored != null && !stored.isBlank() && !stored.equalsIgnoreCase(requestHash)) {
					throw new SalesIdempotencyPayloadConflictException();
				}
				return find(tenantId, workspaceId, null, rs.getObject(1).toString());
			}, uuid(tenantId), uuid(workspaceId), uuid(actorMembershipId), idempotencyKey);
	}

	@Override
	public Optional<ApprovedPurchaseRequestSnapshot> loadApprovedSnapshot(String tenantId, String workspaceId,
			String purchaseRequestId, long expectedVersion) {
		UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), request = uuid(purchaseRequestId);
		PurchaseRequestRow pr = jdbc.query("select client_account_id,buyer_membership_id,status,version,priority,requested_delivery_date,delivery_profile_snapshot,payment_option,comments from sales.purchase_request where tenant_id=? and workspace_id=? and id=? for update",
				rs -> rs.next() ? new PurchaseRequestRow(rs.getObject(1).toString(), rs.getObject(2).toString(), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getObject(6, java.time.LocalDate.class), rs.getString(7), rs.getString(8), rs.getString(9)) : null,
				tenant, workspace, request);
		if (pr == null) throw new SalesResourceNotFoundException("purchase-request");
		if ("CONVERTED_TO_ORDER".equals(pr.status())) return Optional.empty();
		if (!"APPROVED".equals(pr.status()) || pr.version() != expectedVersion) throw new SalesConcurrencyConflictException();
		List<PurchaseRequestLineRow> requestLines = jdbc.query("select catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency from sales.purchase_request_line where purchase_request_id=? order by created_at,id",
				(rs, row) -> new PurchaseRequestLineRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getString(5), rs.getBigDecimal(6), rs.getString(7)), request);
		if (requestLines.isEmpty()) throw new PurchaseRequestTransitionException();
		String currency = requestLines.getFirst().currency();
		List<SalesOrderLine> lines = requestLines.stream().map(line -> new SalesOrderLine(line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.price(), line.currency(), line.quantity().multiply(line.price()))).toList();
		BigDecimal total = lines.stream().map(line -> line.quantity().multiply(line.unitPriceAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
		return Optional.of(new ApprovedPurchaseRequestSnapshot(new TenantId(tenant), new WorkspaceId(workspace), new ClientAccountId(pr.clientAccountId()),
				new BuyerMembershipId(uuid(pr.buyerMembershipId())), new PurchaseRequestId(purchaseRequestId), lines,
				PurchaseRequestPriority.from(pr.priority()), pr.requestedDeliveryDate(), pr.deliverySnapshot(), PaymentOption.from(pr.paymentOption()), pr.notes(), currency, total));
	}

	@Override
	public Optional<SalesOrderView> findBySourcePurchaseRequest(String tenantId, String workspaceId, String purchaseRequestId) {
		return jdbc.query(orderSql() + " where o.tenant_id=? and o.workspace_id=? and o.source_purchase_request_id=?",
				this::optionalDetail, uuid(tenantId), uuid(workspaceId), uuid(purchaseRequestId));
	}

	@Override
	public SalesOrderConversionPersistencePort.SalesOrderIdentity nextIdentity(String tenantId, String workspaceId) {
		UUID tenant = uuid(tenantId), workspace = uuid(workspaceId);
		int year = Year.now(java.time.ZoneOffset.UTC).getValue();
		long sequence = nextSequence(tenant, workspace, year);
		return new SalesOrderConversionPersistencePort.SalesOrderIdentity(new SalesOrderId(UUID.randomUUID().toString()),
				new SalesOrderNumber(String.format("SO-%04d-%06d", year, sequence)));
	}

	@Override
	public SalesOrderView persistConversion(SalesOrder aggregate, long purchaseRequestVersion, String actorMembershipId,
			String idempotencyKey, String note, long nowEpochMillis) {
		return persistConversion(aggregate, purchaseRequestVersion, actorMembershipId, idempotencyKey, note,
				nowEpochMillis, "");
	}

	@Override
	public SalesOrderView persistConversion(SalesOrder aggregate, long purchaseRequestVersion, String actorMembershipId,
			String idempotencyKey, String note, long nowEpochMillis, String requestHash) {
		UUID orderId = uuid(aggregate.id().value()), tenant = aggregate.tenantId().value(), workspace = aggregate.workspaceId().value();
		UUID request = uuid(aggregate.sourcePurchaseRequestId().value()), actor = uuid(actorMembershipId);
		jdbc.update("insert into sales.sales_order (id,tenant_id,workspace_id,number,client_account_id,created_by_membership_id,buyer_membership_id,source_purchase_request_id,priority,requested_delivery_date,delivery_snapshot,payment_option,notes,currency,total_amount,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',?,?,0)",
				orderId, tenant, workspace, aggregate.number().value(), uuid(aggregate.clientAccountId().value()), actor, aggregate.buyerMembershipId().value(), request, aggregate.priority().name(), aggregate.requestedDeliveryDate(), aggregate.deliverySnapshot(), aggregate.paymentOption() == null ? null : aggregate.paymentOption().name(), aggregate.notes(), aggregate.currency(), aggregate.totalSnapshot(), timestamp(nowEpochMillis), timestamp(nowEpochMillis));
		for (SalesOrderLine line : aggregate.lines()) jdbc.update("insert into sales.sales_order_line (id,sales_order_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), orderId, line.catalogItemId(), line.itemNameSnapshot(), line.presentationSnapshot(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.lineSubtotal(), timestamp(nowEpochMillis));
		if (jdbc.update("update sales.purchase_request set status='CONVERTED_TO_ORDER',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='APPROVED' and version=?",
				timestamp(nowEpochMillis), tenant, workspace, request, purchaseRequestVersion) != 1) throw new SalesConcurrencyConflictException();
		jdbc.update("insert into sales.purchase_request_event (id,purchase_request_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,occurred_at) values (?,?,?,?,?,'CONVERTED_TO_ORDER','APPROVED','CONVERTED_TO_ORDER',?)",
				UUID.randomUUID(), request, tenant, workspace, actor, timestamp(nowEpochMillis));
		jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,to_status,reason,occurred_at) values (?,?,?,?,?,'ORDER_CREATED','PENDING',?,?)",
				UUID.randomUUID(), orderId, tenant, workspace, actor, note, timestamp(nowEpochMillis));
		changeFeed.append(tenant.toString(), workspace.toString(), aggregate.clientAccountId().value(), "purchase_request", request.toString(), "sales.purchase-request.converted", "CONVERTED_TO_ORDER", nowEpochMillis, true);
		changeFeed.append(tenant.toString(), workspace.toString(), aggregate.clientAccountId().value(), "sales_order", orderId.toString(), "sales.sales-order.created", "PENDING", nowEpochMillis, true);
		int inserted = jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,request_hash,created_at) values (?,?,?,?,?,?,?,?,?,?) on conflict (tenant_id,workspace_id,actor_membership_id,operation,idempotency_key) do nothing",
				UUID.randomUUID(), tenant, workspace, actor, "purchase-request-order-conversion", idempotencyKey, orderId, 0, requestHash, timestamp(nowEpochMillis));
		if (inserted != 1) {
			String existingHash = jdbc.queryForObject("select request_hash from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='purchase-request-order-conversion' and idempotency_key=?", String.class, tenant, workspace, actor, idempotencyKey);
			if (existingHash != null && !existingHash.isBlank() && !existingHash.equalsIgnoreCase(requestHash)) {
				throw new SalesIdempotencyPayloadConflictException();
			}
			return findIdempotent(tenant.toString(), workspace.toString(), actor.toString(), idempotencyKey).orElseThrow();
		}
		return find(tenant.toString(), workspace.toString(), null, orderId.toString()).orElseThrow();
	}

	@Override
	public SalesPage<SalesOrderView> list(String tenantId, String workspaceId, String buyerAccountId, SalesOrderFilter filter) {
		String where = " where o.tenant_id=? and o.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		if (buyerAccountId != null) { where += " and o.client_account_id=?"; args.add(uuid(buyerAccountId)); }
		if (filter.status() != null) { where += " and o.status=?"; args.add(filter.status()); }
		if (filter.priority() != null && !filter.priority().isBlank()) { where += " and o.priority=?"; args.add(filter.priority()); }
		if (filter.clientAccountId() != null && !filter.clientAccountId().isBlank()) { where += " and o.client_account_id=?"; args.add(uuid(filter.clientAccountId())); }
		if (filter.search() != null && !filter.search().isBlank()) { where += " and (lower(o.number) like ? or lower(c.code) like ? or lower(c.business_name) like ? or lower(c.commercial_name) like ?)"; String value = "%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%"; args.add(value); args.add(value); args.add(value); args.add(value); }
		if (filter.createdFrom() != null) { where += " and o.created_at >= ?"; args.add(filter.createdFrom()); }
		if (filter.createdTo() != null) { where += " and o.created_at < ?"; args.add(filter.createdTo().plusDays(1)); }
		if (filter.requestedDeliveryFrom() != null) { where += " and o.requested_delivery_date >= ?"; args.add(filter.requestedDeliveryFrom()); }
		if (filter.requestedDeliveryTo() != null) { where += " and o.requested_delivery_date < ?"; args.add(filter.requestedDeliveryTo().plusDays(1)); }
		long total = jdbc.queryForObject("select count(*)" + orderFromSql() + where, Long.class, args.toArray());
		String orderBy = switch (filter.sort()) {
			case "createdAt,asc" -> "o.created_at asc,o.id asc";
			case "updatedAt,asc" -> "o.updated_at asc,o.id asc";
			case "updatedAt,desc" -> "o.updated_at desc,o.id desc";
			case "orderNumber,asc" -> "o.number asc,o.id asc";
			case "orderNumber,desc" -> "o.number desc,o.id desc";
			case "priority,asc" -> "o.priority asc,o.id asc";
			case "priority,desc" -> "o.priority desc,o.id desc";
			case "total,asc" -> "o.total_amount asc,o.id asc";
			case "total,desc" -> "o.total_amount desc,o.id desc";
			case "requestedDeliveryDate,asc" -> "o.requested_delivery_date asc nulls last,o.id asc";
			case "requestedDeliveryDate,desc" -> "o.requested_delivery_date desc nulls last,o.id desc";
			default -> "o.created_at desc,o.id desc";
		};
		List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(filter.size()); pageArgs.add(filter.page() * filter.size());
		List<SalesOrderView> items = jdbc.query(orderSql() + where + " order by " + orderBy + " limit ? offset ?", (rs, row) -> summary(rs), pageArgs.toArray());
		return new SalesPage<>(items, filter.page(), filter.size(), total);
	}

	@Override
	public Optional<SalesOrderView> find(String tenantId, String workspaceId, String buyerAccountId, String id) {
		String scope = buyerAccountId == null ? "" : " and o.client_account_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		if (buyerAccountId != null) args.add(uuid(buyerAccountId)); args.add(uuid(id));
		return jdbc.query(orderSql() + " where o.tenant_id=? and o.workspace_id=?" + scope + " and o.id=?", this::optionalDetail, args.toArray());
	}

	@Override
	public SalesOrderView transition(String tenantId, String workspaceId, String id, String action, String reason,
			String actorMembershipId, long expectedVersion, long nowEpochMillis) {
		UUID orderId = uuid(id); UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), actor = uuid(actorMembershipId);
		String status = switch (action) { case "confirm" -> "CONFIRMED"; case "reject" -> "REJECTED"; case "cancel" -> "CANCELLED"; default -> throw new com.nexa.api.sales.application.exception.SalesOrderTransitionException(); };
		if ("reject".equals(action) && (reason == null || reason.isBlank())) throw new com.nexa.api.sales.application.exception.SalesOrderRejectionReasonRequiredException();
		int changed = jdbc.update("update sales.sales_order set status=?,rejection_reason=?,confirmed_at=case when ?='CONFIRMED' then ? else confirmed_at end,rejected_at=case when ?='REJECTED' then ? else rejected_at end,cancelled_at=case when ?='CANCELLED' then ? else cancelled_at end,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='PENDING' and version=?",
				status, "REJECTED".equals(status) ? reason.trim() : null, status, timestamp(nowEpochMillis), status, timestamp(nowEpochMillis), status, timestamp(nowEpochMillis), timestamp(nowEpochMillis), tenant, workspace, orderId, expectedVersion);
		if (changed != 1) throw new SalesConcurrencyConflictException();
		jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,reason,occurred_at) values (?,?,?,?,?,'ORDER_STATUS_CHANGED','PENDING',?,?,?)",
				UUID.randomUUID(), orderId, tenant, workspace, actor, status, reason, timestamp(nowEpochMillis));
		String client = jdbc.queryForObject("select client_account_id from sales.sales_order where id=?", String.class, orderId);
		String eventType = switch (action) {
			case "confirm" -> "sales.sales-order.confirmed";
			case "reject" -> "sales.sales-order.rejected";
			case "cancel" -> "sales.sales-order.cancelled";
			default -> throw new com.nexa.api.sales.application.exception.SalesOrderTransitionException();
		};
		changeFeed.append(tenantId, workspaceId, client, "sales_order", id, eventType, status, nowEpochMillis, client != null);
		return find(tenantId, workspaceId, null, id).orElseThrow();
	}

	@Override
	public List<SalesOrderEventView> events(String tenantId, String workspaceId, String buyerAccountId, String id) {
		find(tenantId, workspaceId, buyerAccountId, id).orElseThrow(() -> new SalesResourceNotFoundException("sales-order"));
		return jdbc.query("select id,event_type,from_status,to_status,reason,actor_membership_id,occurred_at from sales.sales_order_event where tenant_id=? and workspace_id=? and sales_order_id=? order by occurred_at,id",
				(rs, row) -> new SalesOrderEventView(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6).toString(), rs.getTimestamp(7).toInstant()), uuid(tenantId), uuid(workspaceId), uuid(id));
	}

	@Override
	public SalesPage<FulfillmentCandidateView> fulfillmentCandidates(String tenantId, String workspaceId, SalesOrderFilter filter) {
		String where = " where o.tenant_id=? and o.workspace_id=? and o.status='CONFIRMED'"; List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		long total = jdbc.queryForObject("select count(*) from sales.sales_order o" + where, Long.class, args.toArray());
		List<Object> pageArgs = new ArrayList<>(args); pageArgs.add(filter.size()); pageArgs.add(filter.page() * filter.size());
		List<FulfillmentCandidateView> items = jdbc.query("select o.id,o.number,o.client_account_id from sales.sales_order o" + where + " order by o.created_at desc,o.id desc limit ? offset ?",
				(rs, row) -> candidate(rs), pageArgs.toArray());
		return new SalesPage<>(items, filter.page(), filter.size(), total);
	}

	private Optional<SalesOrderView> findIdempotent(String tenant, String workspace, String actor, String key) {
		return jdbc.query("select resource_id from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation='purchase-request-order-conversion' and idempotency_key=?",
				rs -> rs.next() ? find(tenant, workspace, null, rs.getObject(1).toString()) : Optional.empty(), uuid(tenant), uuid(workspace), uuid(actor), key);
	}
	private void lockConversionIdempotency(String tenant, String workspace, String actor, String key) {
		jdbc.query("select pg_advisory_xact_lock(hashtext(?))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
				tenant + "|" + workspace + "|" + actor + "|purchase-request-order-conversion|" + key);
	}
	private SalesOrder aggregate(SalesOrderView view) {
		List<SalesOrderLine> lines = view.lines().stream().map(line -> new SalesOrderLine(line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.lineSubtotal())).toList();
		return SalesOrder.rehydrate(new SalesOrderId(view.id()), new SalesOrderNumber(view.number()), new TenantId(view.tenantId()), new WorkspaceId(view.workspaceId()), new ClientAccountId(view.clientAccountId()), new BuyerMembershipId(uuid(view.buyerMembershipId())), new PurchaseRequestId(view.sourcePurchaseRequestId()), new BuyerMembershipId(uuid(view.createdByMembershipId())), lines, view.priority(), view.requestedDeliveryDate(), view.deliverySnapshot(), view.paymentOption(), view.notes(), view.currency(), view.total(), view.createdAt(), SalesOrderStatus.valueOf(view.status()), view.confirmedAt(), view.rejectedAt(), view.cancelledAt(), view.rejectionReason(), view.version());
	}
	private long nextSequence(UUID tenant, UUID workspace, int year) {
		jdbc.update("insert into sales.sales_order_sequence (tenant_id,workspace_id,order_year,next_value) values (?,?,?,1) on conflict do nothing", tenant, workspace, year);
		Long value = jdbc.queryForObject("select next_value from sales.sales_order_sequence where tenant_id=? and workspace_id=? and order_year=? for update", Long.class, tenant, workspace, year);
		jdbc.update("update sales.sales_order_sequence set next_value=? where tenant_id=? and workspace_id=? and order_year=?", value + 1, tenant, workspace, year);
		return value;
	}
	private String orderFromSql() { return " from sales.sales_order o left join sales.client_account c on c.id=o.client_account_id and c.tenant_id=o.tenant_id and c.workspace_id=o.workspace_id"; }
	private String orderSql() { return "select o.id,o.number,o.tenant_id,o.workspace_id,o.client_account_id,o.created_by_membership_id,o.buyer_membership_id,o.source_purchase_request_id,o.priority,o.requested_delivery_date,o.delivery_snapshot,o.payment_option,o.notes,o.currency,o.total_amount,o.status,o.created_at,o.updated_at,o.confirmed_at,o.rejected_at,o.cancelled_at,o.rejection_reason,o.version" + orderFromSql(); }
	private SalesOrderView summary(ResultSet rs) throws java.sql.SQLException { return new SalesOrderView(rs.getObject(1).toString(), rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(), rs.getObject(5).toString(), rs.getObject(6).toString(), rs.getObject(7).toString(), rs.getObject(8).toString(), com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority.from(rs.getString(9)), rs.getObject(10, java.time.LocalDate.class), rs.getString(11), com.nexa.api.sales.domain.model.purchaserequest.PaymentOption.from(rs.getString(12)), rs.getString(13), rs.getString(14), rs.getBigDecimal(15), rs.getString(16), rs.getTimestamp(17).toInstant(), rs.getTimestamp(18).toInstant(), rs.getTimestamp(19) == null ? null : rs.getTimestamp(19).toInstant(), rs.getTimestamp(20) == null ? null : rs.getTimestamp(20).toInstant(), rs.getTimestamp(21) == null ? null : rs.getTimestamp(21).toInstant(), rs.getString(22), rs.getLong(23), List.of()); }
	private Optional<SalesOrderView> optionalDetail(ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(detail(rs)) : Optional.empty(); }
	private SalesOrderView detail(ResultSet rs) throws java.sql.SQLException { SalesOrderView summary = summary(rs); List<SalesOrderLineView> lines = jdbc.query("select catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal from sales.sales_order_line where sales_order_id=? order by created_at,id", (line, row) -> new SalesOrderLineView(line.getString(1), line.getString(2), line.getString(3), line.getBigDecimal(4), line.getString(5), line.getBigDecimal(6), line.getString(7), line.getBigDecimal(8)), uuid(summary.id())); return new SalesOrderView(summary.id(), summary.number(), summary.tenantId(), summary.workspaceId(), summary.clientAccountId(), summary.createdByMembershipId(), summary.buyerMembershipId(), summary.sourcePurchaseRequestId(), summary.priority(), summary.requestedDeliveryDate(), summary.deliverySnapshot(), summary.paymentOption(), summary.notes(), summary.currency(), summary.total(), summary.status(), summary.createdAt(), summary.updatedAt(), summary.confirmedAt(), summary.rejectedAt(), summary.cancelledAt(), summary.rejectionReason(), summary.version(), lines); }
	private FulfillmentCandidateView candidate(ResultSet rs) throws java.sql.SQLException { String id = rs.getObject(1).toString(); List<FulfillmentCandidateView.Line> lines = jdbc.query("select catalog_item_id,item_name_snapshot,quantity,unit from sales.sales_order_line where sales_order_id=? order by created_at,id", (line, row) -> new FulfillmentCandidateView.Line(line.getString(1), line.getString(2), line.getBigDecimal(3), line.getString(4)), uuid(id)); return new FulfillmentCandidateView(id, rs.getString(2), rs.getObject(3).toString(), "AWAITING_INVENTORY_RESERVATION", lines); }
	private record PurchaseRequestRow(String clientAccountId, String buyerMembershipId, String status, long version, String priority,
			java.time.LocalDate requestedDeliveryDate, String deliverySnapshot, String paymentOption, String notes) { }
	private record PurchaseRequestLineRow(String catalogItemId, String itemName, String presentation, BigDecimal quantity,
			String unit, BigDecimal price, String currency) { }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
	private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }
}
