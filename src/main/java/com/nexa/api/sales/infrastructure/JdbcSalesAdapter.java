package com.nexa.api.sales.infrastructure;

import com.nexa.api.catalogmanagement.infrastructure.seed.CatalogSeedLoader;
import com.nexa.api.sales.application.model.*;
import com.nexa.api.sales.application.port.out.SalesPort;
import com.nexa.api.sales.domain.CatalogItemSnapshot;
import com.nexa.api.sales.domain.PriceSnapshot;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
public class JdbcSalesAdapter implements SalesPort {
	private final JdbcTemplate jdbc;
	private final CatalogSeedLoader catalog;
	public JdbcSalesAdapter(JdbcTemplate jdbc, CatalogSeedLoader catalog) { this.jdbc = jdbc; this.catalog = catalog; }

	@Override public SalesPage<ClientAccountView> listClientAccounts(String tenant, String workspace, String search, String status, int page, int size) {
		String filter = " where a.tenant_id=? and a.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (search != null && !search.isBlank()) { filter += " and (lower(a.code) like ? or lower(a.business_name) like ? or lower(a.commercial_name) like ?)"; String value = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%"; args.add(value); args.add(value); args.add(value); }
		if (status != null && !status.isBlank()) { filter += " and a.status=?"; args.add(status.toUpperCase(java.util.Locale.ROOT)); }
		long total = jdbc.queryForObject("select count(*) from sales.client_account a" + filter, Long.class, args.toArray());
		args.add(size); args.add(page * size);
		return new SalesPage<>(jdbc.query(accountSql() + filter + " order by a.business_name limit ? offset ?", (rs, row) -> account(rs), args.toArray()), page, size, total);
	}
	@Override public Optional<ClientAccountView> findClientAccount(String tenant, String workspace, String id) {
		return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and a.id=?", this::optionalAccount, uuid(tenant), uuid(workspace), uuid(id));
	}
	@Override public void insertClientAccount(ClientAccountView command, String tenant, String workspace, UUID id, long epoch) {
		Timestamp now = timestamp(epoch);
		jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(tenant), uuid(workspace), command.code(), command.businessName(), command.commercialName(), command.countryCode() == null ? "PE" : command.countryCode(), command.taxType() == null ? "RUC" : command.taxType(), command.taxValue(), command.segment(), command.contactPerson(), command.contactEmail(), command.phone(), command.deliveryProfile(), command.paymentCondition(), "ACTIVE", now, now);
	}
	@Override public int updateClientAccount(String tenant, String workspace, String id, String businessName, String commercialName, String contactPerson, String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version) {
		return jdbc.update("update sales.client_account set business_name=coalesce(?,business_name), commercial_name=coalesce(?,commercial_name), contact_person=coalesce(?,contact_person), contact_email=coalesce(?,contact_email), phone=coalesce(?,phone), delivery_profile=coalesce(?,delivery_profile), payment_condition=coalesce(?,payment_condition), updated_at=current_timestamp, version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", businessName, commercialName, contactPerson, contactEmail, phone, deliveryProfile, paymentCondition, uuid(tenant), uuid(workspace), uuid(id), version);
	}
	@Override public int updateClientAccountStatus(String tenant, String workspace, String id, String status, long version) { return jdbc.update("update sales.client_account set status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", status, uuid(tenant), uuid(workspace), uuid(id), version); }
	@Override public Optional<ClientAccountView> findClientAccountForBuyer(String tenant, String workspace, String membership) { return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=? and a.status='ACTIVE'", this::optionalAccount, uuid(tenant), uuid(workspace), uuid(membership)); }
	@Override public boolean isAvailableBuyerMembership(String tenant, String workspace, String membership) { Integer count = jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id where w.tenant_id=? and m.workspace_id=? and m.id=? and m.role='BUYER' and m.status='ACTIVE'", Integer.class, uuid(tenant), uuid(workspace), uuid(membership)); return count != null && count == 1; }
	@Override public int associateBuyer(String tenant, String workspace, String account, String membership, UUID associationId, long epoch, long version) {
		int inserted = jdbc.update("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?)", uuid(account), uuid(membership), uuid(tenant), uuid(workspace), timestamp(epoch));
		if (inserted != 1) return 0;
		return jdbc.update("update sales.client_account set updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", uuid(tenant), uuid(workspace), uuid(account), version);
	}

	@Override public SalesPage<PurchaseRequestView> listPurchaseRequests(String tenant, String workspace, String buyerAccount, PurchaseRequestFilter filter) {
		String where = " where r.tenant_id=? and r.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (buyerAccount != null) { where += " and r.client_account_id=?"; args.add(uuid(buyerAccount)); }
		if (filter.status() != null && !filter.status().isBlank()) { where += " and r.status=?"; args.add(filter.status().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.priority() != null && !filter.priority().isBlank()) { where += " and r.priority=?"; args.add(filter.priority().toUpperCase(java.util.Locale.ROOT)); }
		if (filter.search() != null && !filter.search().isBlank()) { where += " and lower(r.code) like ?"; args.add("%" + filter.search().toLowerCase(java.util.Locale.ROOT) + "%"); }
		if (filter.createdFrom() != null) { where += " and r.created_at >= ?"; args.add(filter.createdFrom()); }
		if (filter.createdTo() != null) { where += " and r.created_at < ?"; args.add(filter.createdTo().plusDays(1)); }
		long total = jdbc.queryForObject("select count(*) from sales.purchase_request r" + where, Long.class, args.toArray());
		args.add(filter.size()); args.add(filter.page() * filter.size());
		return new SalesPage<>(jdbc.query(requestSql() + where + " order by r.created_at desc limit ? offset ?", (rs, row) -> request(rs), args.toArray()), filter.page(), filter.size(), total);
	}
	@Override public Optional<PurchaseRequestView> findPurchaseRequest(String tenant, String workspace, String buyerAccount, String id) {
		String scope = buyerAccount == null ? "" : " and r.client_account_id=?";
		if (buyerAccount == null) return jdbc.query(requestSql() + " where r.tenant_id=? and r.workspace_id=? and r.id=?", this::optionalRequest, uuid(tenant), uuid(workspace), uuid(id));
		return jdbc.query(requestSql() + " where r.tenant_id=? and r.workspace_id=?" + scope + " and r.id=?", this::optionalRequest, uuid(tenant), uuid(workspace), uuid(buyerAccount), uuid(id));
	}
	@Override public void insertPurchaseRequest(PurchaseRequestView request, String tenant, String workspace, UUID id, long epoch) {
		Timestamp now = timestamp(epoch);
		jdbc.update("insert into sales.purchase_request (id,tenant_id,workspace_id,client_account_id,buyer_membership_id,code,status,priority,requested_delivery_date,delivery_profile_snapshot,payment_option,comments,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(tenant), uuid(workspace), uuid(request.clientAccountId()), uuid(request.buyerMembershipId()), request.code(), request.status(), request.priority(), request.requestedDeliveryDate(), request.deliveryProfileSnapshot(), request.paymentOption(), request.comment(), now, now);
	}
	@Override public void insertLine(String requestId, PurchaseRequestLineView line, UUID id, long epoch) { jdbc.update("insert into sales.purchase_request_line (id,purchase_request_id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(requestId), line.catalogItemId(), line.itemName(), line.presentation(), line.quantity(), line.unit(), line.unitPriceAmount(), line.unitPriceCurrency(), line.notes(), timestamp(epoch), timestamp(epoch)); }
	@Override public int updatePurchaseRequest(String tenant, String workspace, String buyerAccount, String id, String priority, LocalDate date, String delivery, String payment, String comment, long version) {
		String scope = buyerAccount == null ? "" : " and client_account_id=?"; List<Object> args = new ArrayList<>(java.util.Arrays.asList(priority, date, delivery, payment, comment, uuid(tenant), uuid(workspace))); if (buyerAccount != null) args.add(uuid(buyerAccount)); args.add(uuid(id)); args.add(version);
		return jdbc.update("update sales.purchase_request set priority=coalesce(?,priority),requested_delivery_date=coalesce(?,requested_delivery_date),delivery_profile_snapshot=coalesce(?,delivery_profile_snapshot),payment_option=coalesce(?,payment_option),comments=coalesce(?,comments),updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status in ('DRAFT','NEEDS_ADJUSTMENT') and version=?", args.toArray());
	}
	@Override public int updateLine(String requestId, String lineId, java.math.BigDecimal quantity, String notes, long version) {
		int changed = jdbc.update("update sales.purchase_request_line l set quantity=coalesce(?,quantity),notes=coalesce(?,notes),updated_at=current_timestamp,version=version+1 where l.purchase_request_id=? and l.id=? and exists (select 1 from sales.purchase_request r where r.id=l.purchase_request_id and r.version=?)", quantity, notes, uuid(requestId), uuid(lineId), version);
		if (changed == 1) jdbc.update("update sales.purchase_request set updated_at=current_timestamp,version=version+1 where id=? and version=?", uuid(requestId), version);
		return changed;
	}
	@Override public int deleteLine(String requestId, String lineId, long version) {
		int changed = jdbc.update("delete from sales.purchase_request_line l where l.purchase_request_id=? and l.id=? and exists (select 1 from sales.purchase_request r where r.id=l.purchase_request_id and r.version=?)", uuid(requestId), uuid(lineId), version);
		if (changed == 1) jdbc.update("update sales.purchase_request set updated_at=current_timestamp,version=version+1 where id=? and version=?", uuid(requestId), version);
		return changed;
	}
	@Override public int transition(String tenant, String workspace, String buyerAccount, String id, String from, String to, String note, String actor, long version, UUID eventId, long epoch) {
		String scope = buyerAccount == null ? "" : " and client_account_id=?";
		List<Object> transitionArgs = new ArrayList<>(java.util.Arrays.asList(to, note, to, uuid(actor), to, to, uuid(tenant), uuid(workspace))); if (buyerAccount != null) transitionArgs.add(uuid(buyerAccount)); transitionArgs.add(uuid(id)); transitionArgs.add(from); transitionArgs.add(version);
		int changed = jdbc.update("update sales.purchase_request set status=?,review_note=?,reviewed_by_membership_id=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then ? else reviewed_by_membership_id end, submitted_at=case when ?='SUBMITTED' then current_timestamp else submitted_at end, reviewed_at=case when ? in ('IN_REVIEW','NEEDS_ADJUSTMENT','APPROVED','REJECTED') then current_timestamp else reviewed_at end, updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=?" + scope + " and id=? and status=? and version=?", transitionArgs.toArray());
		if (changed == 1) jdbc.update("insert into sales.purchase_request_event (id,purchase_request_id,tenant_id,workspace_id,actor_membership_id,event_type,from_status,to_status,occurred_at) values (?,?,?,?,?,?,?,?,?)", eventId, uuid(id), uuid(tenant), uuid(workspace), uuid(actor), to, from, to, timestamp(epoch));
		return changed;
	}
	@Override public Optional<IdempotencyResult> findIdempotency(String tenant, String workspace, String actor, String operation, String key) { return jdbc.query("select resource_id,response_version from sales.idempotency_record where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=?", rs -> rs.next() ? Optional.of(new IdempotencyResult(rs.getObject(1).toString(), rs.getLong(2))) : Optional.empty(), uuid(tenant), uuid(workspace), uuid(actor), operation, key); }
	@Override public void saveIdempotency(String tenant, String workspace, String actor, String operation, String key, String resource, long version, UUID id, long epoch) { jdbc.update("insert into sales.idempotency_record (id,tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,resource_id,response_version,created_at) values (?,?,?,?,?,?,?,?,?)", id, uuid(tenant), uuid(workspace), uuid(actor), operation, key, uuid(resource), version, timestamp(epoch)); }
	@Override public Optional<CatalogItemSnapshot> findActiveCatalogItem(String id) { return catalog.load().stream().filter(item -> item.catalogItemId().equals(id)).findFirst().map(item -> new CatalogItemSnapshot(item.catalogItemId(), item.itemName(), item.presentation(), new PriceSnapshot(item.unitPriceAmount(), item.unitPriceCurrency()))); }

	private String accountSql() { return "select a.id,a.code,a.business_name,a.commercial_name,a.tax_country_code,a.tax_identifier_type,a.tax_identifier_value,a.segment,a.contact_person,a.contact_email,a.phone,a.delivery_profile,a.payment_condition,a.status,cam.workspace_membership_id,a.version from sales.client_account a left join sales.client_account_membership cam on cam.client_account_id=a.id and cam.workspace_id=a.workspace_id"; }
	private ClientAccountView account(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return account(rs); }
	private ClientAccountView account(java.sql.ResultSet rs) throws java.sql.SQLException { return new ClientAccountView(rs.getObject(1).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getObject(15) == null ? null : rs.getObject(15).toString(),rs.getLong(16)); }
	private Optional<ClientAccountView> optionalAccount(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(account(rs)) : Optional.empty(); }
	private String requestSql() { return "select r.id,r.code,r.client_account_id,r.buyer_membership_id,r.status,r.priority,r.requested_delivery_date,r.delivery_profile_snapshot,r.payment_option,r.comments,r.review_note,r.version from sales.purchase_request r"; }
	private PurchaseRequestView request(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return request(rs); }
	private PurchaseRequestView request(java.sql.ResultSet rs) throws java.sql.SQLException { String id=rs.getObject(1).toString(); return new PurchaseRequestView(id,rs.getString(2),rs.getObject(3).toString(),rs.getObject(4).toString(),rs.getString(5),rs.getString(6),rs.getObject(7,LocalDate.class),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),jdbc.query("select id,catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,notes,version from sales.purchase_request_line where purchase_request_id=? order by created_at",(line,n)->new PurchaseRequestLineView(line.getObject(1).toString(),line.getString(2),line.getString(3),line.getString(4),line.getBigDecimal(5),line.getString(6),line.getBigDecimal(7),line.getString(8),line.getString(9),line.getLong(10)),uuid(id)),rs.getLong(12)); }
	private Optional<PurchaseRequestView> optionalRequest(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(request(rs)) : Optional.empty(); }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
}
