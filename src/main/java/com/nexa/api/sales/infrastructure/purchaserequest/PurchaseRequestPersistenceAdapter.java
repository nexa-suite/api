package com.nexa.api.sales.infrastructure.purchaserequest;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestFilter;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestEventView;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class PurchaseRequestPersistenceAdapter implements PurchaseRequestPersistencePort {
	private final JdbcTemplate jdbc;

	public PurchaseRequestPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public SalesPage<PurchaseRequestView> list(String tenant, String workspace, String buyerAccount, PurchaseRequestFilter filter) {
		String where = " where r.tenant_id=? and r.workspace_id=?";
		List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) { where += " and r.client_account_id=?"; args.add(uuid(buyerAccount)); }
		if (filter.status() != null && !filter.status().isBlank()) { where += " and r.status=?"; args.add(filter.status().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.priority() != null && !filter.priority().isBlank()) { where += " and r.priority=?"; args.add(filter.priority().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.search() != null && !filter.search().isBlank()) { where += " and lower(r.code) like ?"; args.add("%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%"); }
		if (filter.createdFrom() != null) { where += " and r.created_at >= ?"; args.add(filter.createdFrom()); }
		if (filter.createdTo() != null) { where += " and r.created_at < ?"; args.add(filter.createdTo().plusDays(1)); }
		long total = jdbc.queryForObject("select count(*) from sales.purchase_request r" + where, Long.class, args.toArray());
		String orderBy = switch (filter.sort()) {
			case "createdAt,asc" -> "r.created_at asc, r.id asc";
			case "updatedAt,asc" -> "r.updated_at asc, r.id asc";
			case "updatedAt,desc" -> "r.updated_at desc, r.id desc";
			default -> "r.created_at desc, r.id desc";
		};
		List<Object> pageArgs = new ArrayList<>(args);
		pageArgs.add(filter.size());
		pageArgs.add(filter.page() * filter.size());
		List<PurchaseRequestView> parents = jdbc.query(requestSql() + where + " order by " + orderBy + " limit ? offset ?",
				(rs, row) -> parent(rs), pageArgs.toArray());
		Map<String, List<PurchaseRequestLineView>> linesByRequest = linesFor(parents.stream().map(PurchaseRequestView::id).toList());
		List<PurchaseRequestView> result = parents.stream()
				.map(parent -> withLines(parent, linesByRequest.getOrDefault(parent.id(), List.of()))).toList();
		return new SalesPage<>(result, filter.page(), filter.size(), total);
	}

	@Override
	public Optional<PurchaseRequestView> find(String tenant, String workspace, String buyerAccount, String id) {
		String scope = buyerAccount == null ? "" : " and r.client_account_id=?";
		List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) args.add(uuid(buyerAccount));
		args.add(uuid(id));
		return jdbc.query(requestSql() + " where r.tenant_id=? and r.workspace_id=?" + scope + " and r.id=?",
				this::optionalRequest, args.toArray());
	}

	@Override
	public List<PurchaseRequestEventView> events(String tenant, String workspace, String buyerAccount, String id) {
		find(tenant, workspace, buyerAccount, id).orElseThrow(() -> new com.nexa.api.sales.application.exception.SalesResourceNotFoundException("purchase-request"));
		return jdbc.query("select id,event_type,from_status,to_status,actor_membership_id,occurred_at from sales.purchase_request_event where tenant_id=? and workspace_id=? and purchase_request_id=? order by occurred_at,id",
				(rs, row) -> new PurchaseRequestEventView(rs.getObject(1).toString(), rs.getString(2), rs.getString(3), rs.getString(4), rs.getObject(5).toString(), rs.getTimestamp(6).toInstant()), uuid(tenant), uuid(workspace), uuid(id));
	}

	@Override
	public void insert(PurchaseRequestView request, String tenant, String workspace, UUID id, long epoch) {
		Timestamp now = timestamp(epoch);
		jdbc.update("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,requested_delivery_date,delivery_profile_snapshot,payment_option,comments,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
				id, uuid(tenant), uuid(workspace), uuid(request.clientAccountId()), uuid(request.buyerMembershipId()), request.code(), request.status(),
				request.priority(), request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), now, now);
	}

	@Override
	public void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long epoch) {
		jdbc.update("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,0)",
				id, uuid(requestId), line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(),
				line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), timestamp(epoch), timestamp(epoch));
	}

	@Override
	public int update(String tenant, String workspace, String buyerAccount, String id, String priority, LocalDate date,
			String delivery, String payment, String comment, long version) {
		String scope = buyerAccount == null ? "" : " and client_account_id=?";
		List<Object> args = new ArrayList<>(List.of(priority, date, delivery, payment, comment, uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) args.add(uuid(buyerAccount));
		args.add(uuid(id));
		args.add(version);
		return jdbc.update("update sales.purchase_request set priority=coalesce(?,priority),requested_delivery_date=coalesce(?,requested_delivery_date),delivery_profile_snapshot=coalesce(?,delivery_profile_snapshot),payment_option=coalesce(?,payment_option),comments=coalesce(?,comments),updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status in ('DRAFT','NEEDS_ADJUSTMENT') and version=?", args.toArray());
	}

	@Override
	public int updateLine(String tenant, String workspace, String buyerAccount, String requestId, String lineId,
			java.math.BigDecimal quantity, String notes, long version) {
		if (advanceParent(tenant, workspace, buyerAccount, requestId, version) != 1) return 0;
		return jdbc.update("update sales.purchase_request_line l set quantity=?,notes=coalesce(?,notes),updated_at=current_timestamp,version=version+1 where l.purchase_request_id=? and l.id=?",
				quantity, notes, uuid(requestId), uuid(lineId)) == 1 ? 1 : 0;
	}

	@Override
	public int deleteLine(String tenant, String workspace, String buyerAccount, String requestId, String lineId, long version) {
		if (advanceParent(tenant, workspace, buyerAccount, requestId, version) != 1) return 0;
		return jdbc.update("delete from sales.purchase_request_line where purchase_request_id=? and id=?", uuid(requestId), uuid(lineId)) == 1 ? 1 : 0;
	}

	@Override
	public int transition(String tenant, String workspace, String buyerAccount, String id, String from, String to,
			String note, String actor, long version) {
		String scope = buyerAccount == null ? "" : " and client_account_id=?";
		List<Object> args = new ArrayList<>();
		args.add(to);
		args.add(note);
		args.add(to);
		args.add(uuid(actor));
		args.add(to);
		args.add(to);
		args.add(uuid(tenant));
		args.add(uuid(workspace));
		if (buyerAccount != null) args.add(uuid(buyerAccount));
		args.add(uuid(id)); args.add(from); args.add(version);
		return jdbc.update("update sales.purchase_request set status=?,review_note=?,reviewed_by_membership_id=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then ? else reviewed_by_membership_id end,submitted_at=case when ?='SUBMITTED' then current_timestamp else submitted_at end,reviewed_at=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then current_timestamp else reviewed_at end,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status=? and version=?", args.toArray());
	}

	private int advanceParent(String tenant, String workspace, String buyerAccount, String requestId, long version) {
		String scope = buyerAccount == null ? "" : " and client_account_id=?";
		List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) args.add(uuid(buyerAccount));
		args.add(uuid(requestId)); args.add(version);
		return jdbc.update("update sales.purchase_request set updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status in ('DRAFT','NEEDS_ADJUSTMENT') and version=?", args.toArray());
	}

	private String requestSql() {
		return "select r.id,r.code,r.client_account_id,r.buyer_membership_id,r.status,r.priority,r.requested_delivery_date,r.delivery_profile_snapshot,r.payment_option,r.comments,r.review_note,r.version from sales.purchase_request r";
	}

	private PurchaseRequestView parent(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new PurchaseRequestView(rs.getObject(1).toString(), rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(),
				rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), List.of(), rs.getLong(12));
	}

	private PurchaseRequestView request(java.sql.ResultSet rs) throws java.sql.SQLException {
		PurchaseRequestView parent = parent(rs);
		return withLines(parent, linesFor(List.of(parent.id())).getOrDefault(parent.id(), List.of()));
	}

	private Map<String, List<PurchaseRequestLineView>> linesFor(List<String> requestIds) {
		if (requestIds.isEmpty()) return Map.of();
		String placeholders = String.join(",", requestIds.stream().map(ignored -> "?").toList());
		Map<String, List<PurchaseRequestLineView>> result = new HashMap<>();
		jdbc.query("select purchase_request_id,id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,version from sales.purchase_request_line where purchase_request_id in (" + placeholders + ") order by created_at, id",
				(org.springframework.jdbc.core.RowCallbackHandler)
				line -> result.computeIfAbsent(line.getObject(1).toString(), ignored -> new ArrayList<>()).add(new PurchaseRequestLineView(
						line.getObject(2).toString(), line.getString(3), line.getString(4), line.getString(5), line.getBigDecimal(6), line.getString(7),
						line.getBigDecimal(8), line.getString(9), line.getString(10), line.getLong(11))), requestIds.stream().map(PurchaseRequestPersistenceAdapter::uuid).toArray());
		return result;
	}

	private static PurchaseRequestView withLines(PurchaseRequestView parent, List<PurchaseRequestLineView> lines) {
		return new PurchaseRequestView(parent.id(), parent.code(), parent.clientAccountId(), parent.buyerMembershipId(), parent.status(), parent.priority(),
				parent.requestedDeliveryDate(), parent.deliveryProfileSnapshot(), parent.paymentOption(), parent.comment(), parent.reviewNote(), lines, parent.version());
	}
	private Optional<PurchaseRequestView> optionalRequest(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(request(rs)) : Optional.empty(); }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
}
