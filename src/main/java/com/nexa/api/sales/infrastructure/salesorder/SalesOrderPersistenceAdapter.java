package com.nexa.api.sales.infrastructure.salesorder;

import com.nexa.api.sales.application.exception.PurchaseRequestTransitionException;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.salesorder.model.FulfillmentCandidateView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderEventView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.sales.application.salesorder.model.SalesOrderLineView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderView;
import com.nexa.api.sales.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.sales.domain.model.clientaccount.ClientAccountId;
import com.nexa.api.sales.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.sales.domain.model.salesorder.ApprovedPurchaseRequestSnapshot;
import com.nexa.api.sales.domain.model.salesorder.SalesOrder;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderLine;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderNumber;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderStatus;
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
public class SalesOrderPersistenceAdapter implements SalesOrderPersistencePort {
	private final JdbcTemplate jdbc;
	private final ChangeEventPersistencePort changeFeed;

	public SalesOrderPersistenceAdapter(JdbcTemplate jdbc, ChangeEventPersistencePort changeFeed) {
		this.jdbc = jdbc;
		this.changeFeed = changeFeed;
	}

	@Override
	public ConversionResult convertApproved(String tenantId, String workspaceId, String purchaseRequestId,
			long purchaseRequestVersion, String actorMembershipId, String idempotencyKey, String note, long nowEpochMillis) {
		Optional<SalesOrderView> prior = findIdempotent(tenantId, workspaceId, actorMembershipId, idempotencyKey);
		if (prior.isPresent()) return new ConversionResult(prior.get());
		if (note != null && note.length() > 2000) throw new com.nexa.api.sales.domain.exception.SalesInvariantViolation("Conversion note is too long");
		UUID tenant = uuid(tenantId), workspace = uuid(workspaceId), request = uuid(purchaseRequestId), actor = uuid(actorMembershipId);
		var pr = jdbc.query("select client_account_id,buyer_membership_id,status,version from sales.purchase_request where tenant_id=? and workspace_id=? and id=? for update",
				rs -> rs.next() ? new PurchaseRequestRow(rs.getObject(1).toString(), rs.getObject(2).toString(), rs.getString(3), rs.getLong(4)) : null,
				tenant, workspace, request);
		if (pr == null) throw new SalesResourceNotFoundException("purchase-request");
		if ("CONVERTED_TO_ORDER".equals(pr.status())) {
			return new ConversionResult(find(tenantId, workspaceId, null, purchaseRequestId).orElseThrow(() -> new SalesConcurrencyConflictException()));
		}
		if (!"APPROVED".equals(pr.status()) || pr.version() != purchaseRequestVersion) throw new SalesConcurrencyConflictException();
		List<PurchaseRequestLineRow> requestLines = jdbc.query("select catalog_item_id,item_name_snapshot,quantity,unit,unit_price_amount,unit_price_currency from sales.purchase_request_line where purchase_request_id=? order by created_at,id",
				(rs, row) -> new PurchaseRequestLineRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getString(4), rs.getBigDecimal(5), rs.getString(6)), request);
		if (requestLines.isEmpty()) throw new PurchaseRequestTransitionException();
		String currency = requestLines.getFirst().currency();
		List<SalesOrderLine> lines = requestLines.stream().map(line -> new SalesOrderLine(line.catalogItemId(), line.itemName(), line.quantity(), line.unit(), line.price(), line.currency())).toList();
		BigDecimal total = lines.stream().map(line -> line.quantity().multiply(line.unitPriceAmount())).reduce(BigDecimal.ZERO, BigDecimal::add);
		UUID orderId = UUID.randomUUID();
		int year = Year.now(java.time.ZoneOffset.UTC).getValue();
		long sequence = nextSequence(tenant, workspace, year);
		SalesOrder order = SalesOrder.fromApprovedSnapshot(new ApprovedPurchaseRequestSnapshot(new com.nexa.api.tenantmanagement.domain.model.identity.TenantId(tenant),
				new com.nexa.api.tenantmanagement.domain.model.identity.WorkspaceId(workspace), new ClientAccountId(pr.clientAccountId()),
				new BuyerMembershipId(uuid(pr.buyerMembershipId())), new PurchaseRequestId(purchaseRequestId), lines, currency, total),
				new SalesOrderId(orderId.toString()), new SalesOrderNumber(String.format("SO-%04d-%06d", year, sequence)), Instant.ofEpochMilli(nowEpochMillis));
		jdbc.update("insert into sales.sales_order (id,tenant_id,workspace_id,number,client_account_id,buyer_membership_id,source_purchase_request_id,currency,total_amount,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,'PENDING',?,?,0)",
				orderId, tenant, workspace, order.number().value(), uuid(pr.clientAccountId()), uuid(pr.buyerMembershipId()), request, order.currency(), order.totalSnapshot(), timestamp(nowEpochMillis), timestamp(nowEpochMillis));
		for (SalesOrderLine line : lines) jdbc.update("insert into sales.sales_order_line (id,sales_order_id,catalog_item_id,item_name_snapshot,quantity,unit,unit_price_amount,unit_price_currency,created_at) values (?,?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), orderId, line.catalogItemId(), line.itemNameSnapshot(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), timestamp(nowEpochMillis));
		if (jdbc.update("update sales.purchase_request set status='CONVERTED_TO_ORDER',updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='APPROVED' and version=?",
				timestamp(nowEpochMillis), tenant, workspace, request, purchaseRequestVersion) != 1) throw new SalesConcurrencyConflictException();
		jdbc.update("insert into sales.purchase_request_event (id,purchase_request_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,occurred_at) values (?,?,?,?,?,'CONVERTED_TO_ORDER','APPROVED','CONVERTED_TO_ORDER',?)",
				UUID.randomUUID(), request, tenant, workspace, actor, timestamp(nowEpochMillis));
		jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,to_status,reason,occurred_at) values (?,?,?,?,?,'ORDER_CREATED','PENDING',?,?)",
				UUID.randomUUID(), orderId, tenant, workspace, actor, note, timestamp(nowEpochMillis));
		changeFeed.append(tenantId, workspaceId, pr.clientAccountId(), "purchase_request", purchaseRequestId, "purchase_request.converted_to_order", "{\"salesOrderId\":\"" + orderId + "\"}", nowEpochMillis);
		changeFeed.append(tenantId, workspaceId, pr.clientAccountId(), "sales_order", orderId.toString(), "sales_order.created", "{\"status\":\"PENDING\",\"number\":\"" + order.number().value() + "\"}", nowEpochMillis);
		jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,created_at) values (?,?,?,?,?,?,?,?,?)",
				UUID.randomUUID(), tenant, workspace, actor, "purchase-request-order-conversion", idempotencyKey, orderId, 0, timestamp(nowEpochMillis));
		return new ConversionResult(find(tenantId, workspaceId, null, orderId.toString()).orElseThrow());
	}

	@Override
	public SalesPage<SalesOrderView> list(String tenantId, String workspaceId, String buyerAccountId, SalesOrderFilter filter) {
		String where = " where o.tenant_id=? and o.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		if (buyerAccountId != null) { where += " and o.client_account_id=?"; args.add(uuid(buyerAccountId)); }
		if (filter.status() != null) { where += " and o.status=?"; args.add(filter.status()); }
		long total = jdbc.queryForObject("select count(*) from sales.sales_order o" + where, Long.class, args.toArray());
		String orderBy = switch (filter.sort()) {
			case "createdAt,asc" -> "o.created_at asc,o.id asc";
			case "updatedAt,asc" -> "o.updated_at asc,o.id asc";
			case "updatedAt,desc" -> "o.updated_at desc,o.id desc";
			case "number,asc" -> "o.number asc,o.id asc";
			case "number,desc" -> "o.number desc,o.id desc";
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
		int changed = jdbc.update("update sales.sales_order set status=?,rejection_reason=?,confirmed_at=case when ?='CONFIRMED' then ? else confirmed_at end,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and status='PENDING' and version=?",
				status, "REJECTED".equals(status) ? reason.trim() : null, status, timestamp(nowEpochMillis), timestamp(nowEpochMillis), tenant, workspace, orderId, expectedVersion);
		if (changed != 1) throw new SalesConcurrencyConflictException();
		jdbc.update("insert into sales.sales_order_event (id,sales_order_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,reason,occurred_at) values (?,?,?,?,?,'ORDER_STATUS_CHANGED','PENDING',?,?,?)",
				UUID.randomUUID(), orderId, tenant, workspace, actor, status, reason, timestamp(nowEpochMillis));
		String client = jdbc.queryForObject("select client_account_id from sales.sales_order where id=?", String.class, orderId);
		changeFeed.append(tenantId, workspaceId, client, "sales_order", id, "sales_order." + action + "ed", "{\"status\":\"" + status + "\"}", nowEpochMillis);
		return find(tenantId, workspaceId, null, id).orElseThrow();
	}

	@Override
	public List<SalesOrderEventView> events(String tenantId, String workspaceId, String buyerAccountId, String id) {
		find(tenantId, workspaceId, buyerAccountId, id).orElseThrow(() -> new SalesResourceNotFoundException("sales-order"));
		return jdbc.query("select event_type,from_status,to_status,reason,actor_membership_id,occurred_at from sales.sales_order_event where tenant_id=? and workspace_id=? and sales_order_id=? order by occurred_at,id",
				(rs, row) -> new SalesOrderEventView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5).toString(), rs.getTimestamp(6).toInstant()), uuid(tenantId), uuid(workspaceId), uuid(id));
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
	private long nextSequence(UUID tenant, UUID workspace, int year) {
		jdbc.update("insert into sales.sales_order_sequence (tenant_id,workspace_id,order_year,next_value) values (?,?,?,1) on conflict do nothing", tenant, workspace, year);
		Long value = jdbc.queryForObject("select next_value from sales.sales_order_sequence where tenant_id=? and workspace_id=? and order_year=? for update", Long.class, tenant, workspace, year);
		jdbc.update("update sales.sales_order_sequence set next_value=? where tenant_id=? and workspace_id=? and order_year=?", value + 1, tenant, workspace, year);
		return value;
	}
	private String orderSql() { return "select o.id,o.number,o.tenant_id,o.workspace_id,o.client_account_id,o.buyer_membership_id,o.source_purchase_request_id,o.currency,o.total_amount,o.status,o.created_at,o.confirmed_at,o.rejection_reason,o.version from sales.sales_order o"; }
	private SalesOrderView summary(ResultSet rs) throws java.sql.SQLException { return new SalesOrderView(rs.getObject(1).toString(), rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(), rs.getObject(5).toString(), rs.getObject(6).toString(), rs.getObject(7).toString(), rs.getString(8), rs.getBigDecimal(9), rs.getString(10), rs.getTimestamp(11).toInstant(), rs.getTimestamp(12) == null ? null : rs.getTimestamp(12).toInstant(), rs.getString(13), rs.getLong(14), List.of()); }
	private Optional<SalesOrderView> optionalDetail(ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(detail(rs)) : Optional.empty(); }
	private SalesOrderView detail(ResultSet rs) throws java.sql.SQLException { SalesOrderView summary = summary(rs); List<SalesOrderLineView> lines = jdbc.query("select catalog_item_id,item_name_snapshot,quantity,unit,unit_price_amount,unit_price_currency from sales.sales_order_line where sales_order_id=? order by created_at,id", (line, row) -> new SalesOrderLineView(line.getString(1), line.getString(2), line.getBigDecimal(3), line.getString(4), line.getBigDecimal(5), line.getString(6)), uuid(summary.id())); return new SalesOrderView(summary.id(), summary.number(), summary.tenantId(), summary.workspaceId(), summary.clientAccountId(), summary.buyerMembershipId(), summary.sourcePurchaseRequestId(), summary.currency(), summary.total(), summary.status(), summary.createdAt(), summary.confirmedAt(), summary.rejectionReason(), summary.version(), lines); }
	private FulfillmentCandidateView candidate(ResultSet rs) throws java.sql.SQLException { String id = rs.getObject(1).toString(); List<FulfillmentCandidateView.Line> lines = jdbc.query("select catalog_item_id,item_name_snapshot,quantity,unit from sales.sales_order_line where sales_order_id=? order by created_at,id", (line, row) -> new FulfillmentCandidateView.Line(line.getString(1), line.getString(2), line.getBigDecimal(3), line.getString(4)), uuid(id)); return new FulfillmentCandidateView(id, rs.getString(2), rs.getObject(3).toString(), "AWAITING_INVENTORY_RESERVATION", lines); }
	private record PurchaseRequestRow(String clientAccountId, String buyerMembershipId, String status, long version) { }
	private record PurchaseRequestLineRow(String catalogItemId, String itemName, BigDecimal quantity, String unit, BigDecimal price, String currency) { }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
}
