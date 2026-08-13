package com.nexa.api.sales.infrastructure.clientaccount;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.model.SalesPage;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
@Profile("!test")
public class ClientAccountPersistenceAdapter implements ClientAccountPersistencePort {
	private final JdbcTemplate jdbc;
	public ClientAccountPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public SalesPage<ClientAccountView> list(String tenant, String workspace, String search, String status, int page, int size) {
		String filter = " where a.tenant_id=? and a.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (search != null && !search.isBlank()) { filter += " and (lower(a.code) like ? or lower(a.business_name) like ? or lower(a.commercial_name) like ?)"; String value = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%"; args.add(value); args.add(value); args.add(value); }
		if (status != null && !status.isBlank()) { filter += " and a.status=?"; args.add(status.toUpperCase(java.util.Locale.ROOT)); }
		long total = jdbc.queryForObject("select count(*) from sales.client_account a" + filter, Long.class, args.toArray()); args.add(size); args.add(page * size);
		return new SalesPage<>(jdbc.query(accountSql() + filter + " order by a.business_name limit ? offset ?", (rs, row) -> account(rs), args.toArray()), page, size, total);
	}
	@Override public Optional<ClientAccountView> find(String tenant, String workspace, String id) { return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and a.id=?", this::optionalAccount, uuid(tenant), uuid(workspace), uuid(id)); }
	@Override public void insert(ClientAccountView command, String tenant, String workspace, UUID id, long epoch) { Timestamp now = timestamp(epoch); jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)", id, uuid(tenant), uuid(workspace), command.code(), command.businessName(), command.commercialName(), command.countryCode() == null ? "PE" : command.countryCode(), command.taxType() == null ? "RUC" : command.taxType(), command.taxValue(), command.segment(), command.contactPerson(), command.contactEmail(), command.phone(), command.deliveryProfile(), command.paymentCondition(), "ACTIVE", now, now); }
	@Override public int update(String tenant, String workspace, String id, String businessName, String commercialName, String contactPerson, String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version) { return jdbc.update("update sales.client_account set business_name=coalesce(?,business_name), commercial_name=coalesce(?,commercial_name), contact_person=coalesce(?,contact_person), contact_email=coalesce(?,contact_email), phone=coalesce(?,phone), delivery_profile=coalesce(?,delivery_profile), payment_condition=coalesce(?,payment_condition), updated_at=current_timestamp, version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", businessName, commercialName, contactPerson, contactEmail, phone, deliveryProfile, paymentCondition, uuid(tenant), uuid(workspace), uuid(id), version); }
	@Override public int updateStatus(String tenant, String workspace, String id, String status, long version) { return jdbc.update("update sales.client_account set status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", status, uuid(tenant), uuid(workspace), uuid(id), version); }
	@Override public Optional<ClientAccountView> findForBuyer(String tenant, String workspace, String membership) { return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=? and a.status='ACTIVE'", this::optionalAccount, uuid(tenant), uuid(workspace), uuid(membership)); }
	@Override public List<BuyerMembershipCandidate> buyerMembershipCandidates(String tenant, String workspace) {
		return jdbc.query("select m.id,u.email,u.display_name from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id join iam.user_account u on u.id=m.user_id where w.tenant_id=? and m.workspace_id=? and m.membership_type='BUYER' and m.status='ACTIVE' order by u.display_name,u.email,m.id",
			(rs, row) -> new BuyerMembershipCandidate(rs.getObject(1).toString(), rs.getString(2), rs.getString(3)), uuid(tenant), uuid(workspace));
	}
	@Override public boolean isAvailableBuyerMembership(String tenant, String workspace, String membership) { Integer count = jdbc.queryForObject("select count(*) from tenant_management.workspace_membership m join tenant_management.workspace w on w.id=m.workspace_id where w.tenant_id=? and m.workspace_id=? and m.id=? and m.membership_type='BUYER' and m.status='ACTIVE'", Integer.class, uuid(tenant), uuid(workspace), uuid(membership)); return count != null && count == 1; }
	@Override public int associateBuyer(String tenant, String workspace, String account, String membership, UUID associationId, long epoch, long version) { int inserted = jdbc.update("insert into sales.client_account_membership (client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?)", uuid(account), uuid(membership), uuid(tenant), uuid(workspace), timestamp(epoch)); if (inserted != 1) return 0; return jdbc.update("update sales.client_account set updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", uuid(tenant), uuid(workspace), uuid(account), version); }
	private String accountSql() { return "select a.id,a.code,a.business_name,a.commercial_name,a.tax_country_code,a.tax_identifier_type,a.tax_identifier_value,a.segment,a.contact_person,a.contact_email,a.phone,a.delivery_profile,a.payment_condition,a.status,cam.workspace_membership_id,a.version from sales.client_account a left join sales.client_account_membership cam on cam.client_account_id=a.id and cam.tenant_id=a.tenant_id and cam.workspace_id=a.workspace_id"; }
	private ClientAccountView account(java.sql.ResultSet rs) throws java.sql.SQLException { return new ClientAccountView(rs.getObject(1).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getObject(15) == null ? null : rs.getObject(15).toString(),rs.getLong(16)); }
	private Optional<ClientAccountView> optionalAccount(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(account(rs)) : Optional.empty(); }
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
}
