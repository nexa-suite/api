package com.nexa.api.sales.infrastructure.purchaserequest;

import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.application.purchaserequest.model.*;
import com.nexa.api.sales.application.purchaserequest.port.PurchaseRequestPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class PurchaseRequestPersistenceAdapter implements PurchaseRequestPersistencePort {
	private final JdbcTemplate jdbc;
	public PurchaseRequestPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public SalesPage<PurchaseRequestView> list(String tenant, String workspace, String buyerAccount, PurchaseRequestFilter filter) {
		String where = " where r.tenant_id=? and r.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) { where += " and r.client_account_id=?"; args.add(uuid(buyerAccount)); }
		if (filter.status() != null && !filter.status().isBlank()) { where += " and r.status=?"; args.add(filter.status().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.priority() != null && !filter.priority().isBlank()) { where += " and r.priority=?"; args.add(filter.priority().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.search() != null && !filter.search().isBlank()) { where += " and lower(r.code) like ?"; args.add("%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%"); }
		if (filter.createdFrom() != null) { where += " and r.created_at >= ?"; args.add(filter.createdFrom()); }
		if (filter.createdTo() != null) { where += " and r.created_at < ?"; args.add(filter.createdTo().plusDays(1)); }
		long total = jdbc.queryForObject("select count(*) from sales.purchase_request r" + where, Long.class, args.toArray()); args.add(filter.size()); args.add(filter.page() * filter.size());
		return new SalesPage<>(jdbc.query(requestSql() + where + " order by r.created_at desc limit ? offset ?", (rs, row) -> request(rs), args.toArray()), filter.page(), filter.size(), total);
	}
	@Override public Optional<PurchaseRequestView> find(String tenant, String workspace, String buyerAccount, String id) {
		if (buyerAccount == null) return jdbc.query(requestSql() + " where r.tenant_id=? and r.workspace_id=? and r.id=?", this::optionalRequest, uuid(tenant), uuid(workspace), uuid(id));
		return jdbc.query(requestSql() + " where r.tenant_id=? and r.workspace_id=? and r.client_account_id=? and r.id=?", this::optionalRequest, uuid(tenant), uuid(workspace), uuid(buyerAccount), uuid(id));
	}
	@Override public void insert(PurchaseRequestView request, String tenant, String workspace, UUID id, long epoch) { Timestamp now = timestamp(epoch); jdbc.update("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,requested_delivery_date,delivery_profile_snapshot,payment_option,comments,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(tenant), uuid(workspace), uuid(request.clientAccountId()), uuid(request.buyerMembershipId()), request.code(), request.status(), request.priority(), request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), now, now); }
	@Override public void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long epoch) { jdbc.update("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(requestId), line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), timestamp(epoch), timestamp(epoch)); }
	@Override public int update(String tenant, String workspace, String buyerAccount, String id, String priority, LocalDate date, String delivery, String payment, String comment, long version) { String scope = buyerAccount == null ? "" : " and client_account_id=?"; List<Object> args = new ArrayList<>(Arrays.asList(priority, date, delivery, payment, comment, uuid(tenant), uuid(workspace))); if (buyerAccount != null) args.add(uuid(buyerAccount)); args.add(uuid(id)); args.add(version); return jdbc.update("update sales.purchase_request set priority=coalesce(?,priority),requested_delivery_date=coalesce(?,requested_delivery_date),delivery_profile_snapshot=coalesce(?,delivery_profile_snapshot),payment_option=coalesce(?,payment_option),comments=coalesce(?,comments),updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status in ('DRAFT','NEEDS_ADJUSTMENT') and version=?", args.toArray()); }
	@Override public int updateLine(String requestId, String lineId, java.math.BigDecimal quantity, String notes, long version) { int changed = jdbc.update("update sales.purchase_request_line l set quantity=coalesce(?,quantity),notes=coalesce(?,notes),updated_at=current_timestamp,version=version+1 where l.purchase_request_id=? and l.id=? and exists (select 1 from sales.purchase_request r where r.id=l.purchase_request_id and r.version=?)", quantity, notes, uuid(requestId), uuid(lineId), version); if (changed == 1) jdbc.update("update sales.purchase_request set updated_at=current_timestamp,version=version+1 where id=? and version=?", uuid(requestId), version); return changed; }
	@Override public int deleteLine(String requestId, String lineId, long version) { int changed = jdbc.update("delete from sales.purchase_request_line l where l.purchase_request_id=? and l.id=? and exists (select 1 from sales.purchase_request r where r.id=l.purchase_request_id and r.version=?)", uuid(requestId), uuid(lineId), version); if (changed == 1) jdbc.update("update sales.purchase_request set updated_at=current_timestamp,version=version+1 where id=? and version=?", uuid(requestId), version); return changed; }
	@Override public int transition(String tenant, String workspace, String buyerAccount, String id, String from, String to, String note, String actor, long version) { String scope = buyerAccount == null ? "" : " and client_account_id=?"; List<Object> args = new ArrayList<>(Arrays.asList(to, note, to, uuid(actor), to, to, uuid(tenant), uuid(workspace))); if (buyerAccount != null) args.add(uuid(buyerAccount)); args.add(uuid(id)); args.add(from); args.add(version); return jdbc.update("update sales.purchase_request set status=?,review_note=?,reviewed_by_membership_id=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then ? else reviewed_by_membership_id end, submitted_at=case when ?='SUBMITTED' then current_timestamp else submitted_at end, reviewed_at=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then current_timestamp else reviewed_at end, updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status=? and version=?", args.toArray()); }
	private String requestSql() { return "select r.id,r.code,r.client_account_id,r.buyer_membership_id,r.status,r.priority,r.requested_delivery_date,r.delivery_profile_snapshot,r.payment_option,r.comments,r.review_note,r.version from sales.purchase_request r"; }
	private PurchaseRequestView request(java.sql.ResultSet rs) throws java.sql.SQLException { String id=rs.getObject(1).toString(); return new PurchaseRequestView(id,rs.getString(2),rs.getObject(3).toString(),rs.getObject(4).toString(),rs.getString(5),rs.getString(6),rs.getObject(7,LocalDate.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),jdbc.query("select id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,version from sales.purchase_request_line where purchase_request_id=? order by created_at",(line,n)->new PurchaseRequestLineView(line.getObject(1).toString(),line.getString(2),line.getString(3),line.getString(4),line.getBigDecimal(5),line.getString(6),line.getBigDecimal(7),line.getString(8),line.getString(9),line.getLong(10)),uuid(id)),rs.getLong(12)); }
	private Optional<PurchaseRequestView> optionalRequest(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(request(rs)) : Optional.empty(); }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
}
