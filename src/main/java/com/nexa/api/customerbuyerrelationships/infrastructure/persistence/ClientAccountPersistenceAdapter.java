package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.catalogcommercialpolicy.application.publicapi.CustomerTermsQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.List;

@Repository
@Profile("!test")
public class ClientAccountPersistenceAdapter implements ClientAccountPersistencePort, CustomerAccountQuery, CustomerTermsQuery {
	private final JdbcTemplate jdbc;
	public ClientAccountPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
	@Override public CustomerAccountPage<ClientAccountView> list(String tenant, String workspace, String search, String status, int page, int size) {
		return list(tenant, workspace, null, search, status, page, size);
	}
	@Override public CustomerAccountPage<ClientAccountView> listForSalesOwner(String tenant, String workspace,
			String salesOwnerMembershipId, String search, String status, int page, int size) {
		return list(tenant, workspace, uuid(salesOwnerMembershipId), search, status, page, size);
	}
	private CustomerAccountPage<ClientAccountView> list(String tenant, String workspace, UUID salesOwnerMembershipId,
			String search, String status, int page, int size) {
		String filter = " where a.tenant_id=? and a.workspace_id=?"; List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace)));
		if (salesOwnerMembershipId != null) { filter += " and a.sales_owner_membership_id=?"; args.add(salesOwnerMembershipId); }
		if (search != null && !search.isBlank()) { filter += " and (lower(a.code) like ? or lower(a.business_name) like ? or lower(a.commercial_name) like ?)"; String value = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%"; args.add(value); args.add(value); args.add(value); }
		if (status != null && !status.isBlank()) { filter += " and a.status=?"; args.add(status.toUpperCase(java.util.Locale.ROOT)); }
		long total = jdbc.queryForObject("select count(*) from sales.client_account a" + filter, Long.class, args.toArray()); args.add(size); args.add(page * size);
		return new CustomerAccountPage<>(jdbc.query(accountSql() + filter + " order by a.business_name limit ? offset ?", (rs, row) -> account(rs), args.toArray()), page, size, total);
	}
	@Override public Optional<CustomerAccountReference> findReference(String tenant, String workspace, String id) {
		return find(tenant, workspace, id).filter(value -> "ACTIVE".equals(value.status()))
				.map(value -> new CustomerAccountReference(value.id(), value.status()));
	}
	@Override public Optional<CustomerAccountReference> lockReference(String tenant, String workspace, String id) {
		return jdbc.query("select a.id,a.status from sales.client_account a where a.tenant_id=? and a.workspace_id=? "
				+ "and a.id=? for share",
				(rs, ignored) -> new CustomerAccountReference(rs.getObject(1).toString(), rs.getString(2)),
				uuid(tenant), uuid(workspace), uuid(id)).stream().findFirst();
	}
	@Override public Optional<CustomerAccountReference> findSalesOwnerReference(String tenant, String workspace,
			String id, String salesOwnerMembershipId) {
		return jdbc.query("select a.id,a.status from sales.client_account a where a.tenant_id=? and a.workspace_id=? "
				+ "and a.id=? and a.sales_owner_membership_id=? and a.status='ACTIVE'",
				(rs, ignored) -> new CustomerAccountReference(rs.getObject(1).toString(), rs.getString(2)),
				uuid(tenant), uuid(workspace), uuid(id), uuid(salesOwnerMembershipId)).stream().findFirst();
	}
	@Override public List<String> lockActiveSalesOwnerAccountIds(String tenant, String workspace,
			String salesOwnerMembershipId) {
		return jdbc.query("select a.id from sales.client_account a where a.tenant_id=? and a.workspace_id=? "
				+ "and a.sales_owner_membership_id=? and a.status='ACTIVE' order by a.id for share",
				(rs, ignored) -> rs.getObject(1, UUID.class).toString(),
				uuid(tenant), uuid(workspace), uuid(salesOwnerMembershipId));
	}
	@Override public Optional<CustomerAccountReference> findBuyerReference(String tenant, String workspace, String membership) {
		return findForBuyer(tenant, workspace, membership).map(value -> new CustomerAccountReference(value.id(), value.status()));
	}
	@Override public boolean lockAndCheckActiveBuyerPaymentSetupRelationship(
			String tenant, String workspace, String membership, String clientAccount) {
		lockBuyerPaymentSetupAuthorization(tenant, workspace, clientAccount);
		Boolean active = jdbc.queryForObject("select exists (select 1 from sales.client_account_membership member "
				+ "join sales.client_account account on account.tenant_id=member.tenant_id "
				+ "and account.workspace_id=member.workspace_id and account.id=member.client_account_id "
				+ "where member.tenant_id=? and member.workspace_id=? and member.workspace_membership_id=? "
				+ "and member.client_account_id=? and member.status='ACTIVE' and account.status='ACTIVE')",
				Boolean.class, uuid(tenant), uuid(workspace), uuid(membership), uuid(clientAccount));
		return Boolean.TRUE.equals(active);
	}
	@Override public Optional<ClientAccountView> find(String tenant, String workspace, String id) { return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and a.id=?", this::optionalAccount, uuid(tenant), uuid(workspace), uuid(id)); }
	@Override public Optional<ClientAccountView> findOwnedBySalesOwner(String tenant, String workspace, String id,
			String salesOwnerMembershipId) {
		return jdbc.query(accountSql() + " where a.tenant_id=? and a.workspace_id=? and a.id=? and a.sales_owner_membership_id=?",
				this::optionalAccount, uuid(tenant), uuid(workspace), uuid(id), uuid(salesOwnerMembershipId));
	}
	@Override public void insert(ClientAccountView command, String tenant, String workspace, UUID id,
			UUID salesOwnerMembershipId, long epoch) {
		Timestamp now = timestamp(epoch);
		String accountType = command.accountType() == null ? "BUSINESS" : command.accountType().name();
		jdbc.update("insert into sales.client_account (id,tenant_id,workspace_id,code,business_name,commercial_name,tax_country_code,tax_identifier_type,tax_identifier_value,segment,contact_person,contact_email,phone,delivery_profile,payment_condition,status,sales_owner_membership_id,account_type,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0)",
				id, uuid(tenant), uuid(workspace), command.code(), command.businessName(), command.commercialName(),
				command.countryCode() == null && "BUSINESS".equals(accountType) ? "PE" : command.countryCode(),
				command.taxType() == null && "BUSINESS".equals(accountType) ? "RUC" : command.taxType(),
				command.taxValue(), command.segment(), command.contactPerson(), command.contactEmail(), command.phone(),
				command.deliveryProfile(), command.paymentCondition(), "ACTIVE", salesOwnerMembershipId,
				accountType, now, now);
	}
	@Override public int update(String tenant, String workspace, String id, String businessName, String commercialName, String contactPerson, String contactEmail, String phone, String deliveryProfile, String paymentCondition, long version) { return jdbc.update("update sales.client_account set business_name=coalesce(?,business_name), commercial_name=coalesce(?,commercial_name), contact_person=coalesce(?,contact_person), contact_email=coalesce(?,contact_email), phone=coalesce(?,phone), delivery_profile=coalesce(?,delivery_profile), payment_condition=coalesce(?,payment_condition), updated_at=current_timestamp, version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", businessName, commercialName, contactPerson, contactEmail, phone, deliveryProfile, paymentCondition, uuid(tenant), uuid(workspace), uuid(id), version); }
	@Override public void lockBuyerPaymentSetupAuthorization(String tenant, String workspace, String clientAccount) {
		String key = tenant + "|" + workspace + "|buyer-payment-setup-auth|" + clientAccount;
		jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))",
				(org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> {
					rs.next();
					return null;
				}, key);
	}
	@Override public int updateStatus(String tenant, String workspace, String id, String status, long version) { return jdbc.update("update sales.client_account set status=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?", status, uuid(tenant), uuid(workspace), uuid(id), version); }
	@Override public int updateSalesOwner(String tenant, String workspace, String id, UUID salesOwnerMembershipId, long version) {
		return jdbc.update("update sales.client_account set sales_owner_membership_id=?,updated_at=current_timestamp,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
			salesOwnerMembershipId, uuid(tenant), uuid(workspace), uuid(id), version);
	}
	@Override public Optional<ClientAccountView> findForBuyer(String tenant, String workspace, String membership) {
		return jdbc.query(accountSqlForMembership() + " where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=? "
				+ "and cam.status='ACTIVE' and a.status='ACTIVE'", this::optionalAccount,
			uuid(membership), uuid(tenant), uuid(workspace), uuid(membership));
	}
	@Override public Optional<ClientAccountView> findAnyForBuyer(String tenant, String workspace, String membership) {
		return jdbc.query(accountSqlForMembership() + " where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=?",
				this::optionalAccount, uuid(membership), uuid(tenant), uuid(workspace), uuid(membership));
	}
	@Override public boolean isExactBuyerRegistration(String tenant, String workspace, String membership,
			ClientAccountView requested) {
		String sql = "select exists (select 1 from sales.client_account a "
				+ "join sales.client_account_membership cam on cam.tenant_id=a.tenant_id "
				+ "and cam.workspace_id=a.workspace_id and cam.client_account_id=a.id "
				+ "where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=? "
				+ "and cam.status='ACTIVE' and a.status='ACTIVE' and a.account_type=? "
				+ "and a.code is not distinct from ? and a.business_name is not distinct from ? "
				+ "and a.commercial_name is not distinct from ? and a.tax_country_code is not distinct from ? "
				+ "and a.tax_identifier_type is not distinct from ? and a.tax_identifier_value is not distinct from ? "
				+ "and a.segment is not distinct from ? and a.contact_person is not distinct from ? "
				+ "and a.contact_email is not distinct from ? and a.phone is not distinct from ? "
				+ "and a.delivery_profile is not distinct from ? and a.payment_condition is not distinct from ?)";
		return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, uuid(tenant), uuid(workspace),
				uuid(membership), requested.accountType().name(), requested.code(), requested.businessName(),
				requested.commercialName(), requested.countryCode(), requested.taxType(), requested.taxValue(),
				requested.segment(), requested.contactPerson(), requested.contactEmail(), requested.phone(),
				requested.deliveryProfile(), requested.paymentCondition()));
	}
	@Override public boolean isBuyerMembershipAssigned(String tenant, String workspace, String membership) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sales.client_account_membership "
				+ "where tenant_id=? and workspace_id=? and workspace_membership_id=?)",
				Boolean.class, uuid(tenant), uuid(workspace), uuid(membership)));
	}
	@Override public ClientAccountBuyerMembershipPage listBuyerMemberships(String tenant, String workspace,
			String account, String salesOwnerMembershipId, int page, int size) {
		String sql = "select a.version as account_version, "
				+ "(select count(*) from sales.client_account_membership total_member where total_member.tenant_id=a.tenant_id "
				+ "and total_member.workspace_id=a.workspace_id and total_member.client_account_id=a.id) as total, "
				+ "member.workspace_membership_id,member.status,member.created_at,member.updated_at "
				+ "from sales.client_account a left join lateral (select link.workspace_membership_id,link.status,"
				+ "link.created_at,link.updated_at from sales.client_account_membership link "
				+ "where link.tenant_id=a.tenant_id and link.workspace_id=a.workspace_id and link.client_account_id=a.id "
				+ "order by link.created_at,link.workspace_membership_id limit ? offset ?) member on true "
				+ "where a.tenant_id=? and a.workspace_id=? and a.id=?";
		// Query placeholders in the lateral subquery occur before outer predicates.
		List<Object> orderedArgs = new ArrayList<>(List.of(size, page * size, uuid(tenant), uuid(workspace), uuid(account)));
		if (salesOwnerMembershipId != null) {
			sql += " and a.sales_owner_membership_id=?";
			orderedArgs.add(uuid(salesOwnerMembershipId));
		}
		List<BuyerMembershipPageRow> rows = jdbc.query(sql,
				(rs, row) -> {
					UUID membershipId = rs.getObject("workspace_membership_id", UUID.class);
					ClientAccountBuyerMembershipView membership = membershipId == null ? null
							: new ClientAccountBuyerMembershipView(membershipId.toString(), rs.getString("status"), null, null,
								rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
					return new BuyerMembershipPageRow(rs.getLong("account_version"), rs.getLong("total"), membership);
				}, orderedArgs.toArray());
		if (rows.isEmpty()) return new ClientAccountBuyerMembershipPage(List.of(), page, size, 0, -1);
		BuyerMembershipPageRow first = rows.getFirst();
		List<ClientAccountBuyerMembershipView> items = rows.stream().map(BuyerMembershipPageRow::membership)
				.filter(java.util.Objects::nonNull).toList();
		return new ClientAccountBuyerMembershipPage(items, page, size, first.total(), first.accountVersion());
	}
	@Override public Optional<ClientAccountBuyerMembershipView> findBuyerMembership(
			String tenant, String workspace, String account, String membership, String salesOwnerMembershipId) {
		String ownerFilter = salesOwnerMembershipId == null ? "" : " and a.sales_owner_membership_id=?";
		List<Object> args = new ArrayList<>(List.of(uuid(tenant), uuid(workspace), uuid(account), uuid(membership)));
		if (salesOwnerMembershipId != null) args.add(uuid(salesOwnerMembershipId));
		return jdbc.query("select member.workspace_membership_id,member.status,member.created_at,member.updated_at "
				+ "from sales.client_account_membership member join sales.client_account a on a.tenant_id=member.tenant_id "
				+ "and a.workspace_id=member.workspace_id and a.id=member.client_account_id "
				+ "where member.tenant_id=? and member.workspace_id=? and member.client_account_id=? "
				+ "and member.workspace_membership_id=?" + ownerFilter,
			(rs, row) -> new ClientAccountBuyerMembershipView(rs.getObject("workspace_membership_id", UUID.class).toString(),
					rs.getString("status"), null, null, rs.getTimestamp("created_at").toInstant(),
					rs.getTimestamp("updated_at").toInstant()),
			args.toArray()).stream().findFirst();
	}
	@Override public int associateBuyerMembership(String tenant, String workspace, String account, String membership,
			String salesOwnerMembershipId, long epoch, long accountVersion) {
		UUID tenantId = uuid(tenant), workspaceId = uuid(workspace), accountId = uuid(account), membershipId = uuid(membership);
		Timestamp now = timestamp(epoch);
		if (bumpAccountVersion(tenantId, workspaceId, accountId, salesOwnerMembershipId, now, accountVersion) != 1) {
			return 0;
		}
		return jdbc.update("insert into sales.client_account_membership "
				+ "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at,status,updated_at,version) "
				+ "values (?,?,?,?,?,'ACTIVE',?,0) on conflict do nothing",
			accountId, membershipId, tenantId, workspaceId, now, now) == 1 ? 1 : 0;
	}
	@Override public int changeBuyerMembershipStatus(String tenant, String workspace, String account, String membership,
			String salesOwnerMembershipId, String expectedStatus, String newStatus, long epoch, long accountVersion) {
		UUID tenantId = uuid(tenant), workspaceId = uuid(workspace), accountId = uuid(account), membershipId = uuid(membership);
		Timestamp now = timestamp(epoch);
		if (bumpAccountVersion(tenantId, workspaceId, accountId, salesOwnerMembershipId, now, accountVersion) != 1) {
			return 0;
		}
		return jdbc.update("update sales.client_account_membership set status=?,updated_at=?,version=version+1 "
				+ "where tenant_id=? and workspace_id=? and client_account_id=? and workspace_membership_id=? and status=?",
			newStatus, now, tenantId, workspaceId, accountId, membershipId, expectedStatus);
	}
	private int bumpAccountVersion(UUID tenant, UUID workspace, UUID account, String salesOwnerMembershipId,
			Timestamp now, long version) {
		String ownerFilter = salesOwnerMembershipId == null ? "" : " and sales_owner_membership_id=?";
		List<Object> args = new ArrayList<>(List.of(now, tenant, workspace, account, version));
		if (salesOwnerMembershipId != null) args.add(uuid(salesOwnerMembershipId));
		return jdbc.update("update sales.client_account set updated_at=?,version=version+1 where tenant_id=? "
				+ "and workspace_id=? and id=? and version=?" + ownerFilter, args.toArray());
	}
	@Override public Optional<CustomerAccountDetails> findActiveDetails(String tenant, String workspace, String id) {
		return details(tenant, workspace, "a.id=? and a.status='ACTIVE'", uuid(id));
	}
	@Override public Optional<CustomerAccountDetails> findActiveBuyerDetails(String tenant, String workspace, String membership) {
		return details(tenant, workspace, "exists (select 1 from sales.client_account_membership cam "
				+ "where cam.tenant_id=a.tenant_id and cam.workspace_id=a.workspace_id and cam.client_account_id=a.id "
				+ "and cam.workspace_membership_id=? and cam.status='ACTIVE') and a.status='ACTIVE'", uuid(membership));
	}
	@Override public Optional<CustomerAccountDetails> findHistoricalDetails(String tenant, String workspace, String id) {
		return details(tenant, workspace, "a.id=?", uuid(id));
	}
    @Override
    public List<String> findRelatedAccountIds(String tenant, String workspace, String membership) {
        return jdbc.query("select client_account_id from sales.client_account_membership where tenant_id=? and workspace_id=? and workspace_membership_id=? order by client_account_id",
                (rs, row) -> rs.getObject(1, UUID.class).toString(), uuid(tenant), uuid(workspace), uuid(membership));
    }
    @Override
    public Optional<String> findUnfilteredReferenceForMembership(String tenant, String workspace, String membership) {
        return jdbc.query("select client_account_id from sales.client_account_membership where tenant_id=? and workspace_id=? and workspace_membership_id=?",
                (rs, row) -> rs.getObject(1, UUID.class).toString(), uuid(tenant), uuid(workspace), uuid(membership))
                .stream().findFirst();
    }

	@Override public List<String> findHistoricalIdsMatching(String tenant, String workspace, String search) {
		if (search == null || search.isBlank()) return List.of();
		String value = "%" + search.toLowerCase(java.util.Locale.ROOT) + "%";
		return jdbc.query("select a.id from sales.client_account a where a.tenant_id=? and a.workspace_id=? "
				+ "and (lower(a.code) like ? or lower(a.business_name) like ? or lower(a.commercial_name) like ?)",
				(rs, ignored) -> rs.getObject(1).toString(), uuid(tenant), uuid(workspace), value, value, value);
	}
	@Override public Optional<CustomerTermsSnapshot> findTerms(String tenant, String workspace, String customerAccountId) {
		return jdbc.query("select a.payment_condition from sales.client_account a where a.tenant_id=? and a.workspace_id=? and a.id=?",
				(rs, ignored) -> new CustomerTermsSnapshot(rs.getString(1)), uuid(tenant), uuid(workspace), uuid(customerAccountId))
				.stream().findFirst();
	}
	@Override public int associateBuyer(String tenant, String workspace, String account, String membership, UUID associationId, long epoch, long version) {
		int inserted = jdbc.update("insert into sales.client_account_membership "
				+ "(client_account_id,workspace_membership_id,tenant_id,workspace_id,created_at) values (?,?,?,?,?) "
				+ "on conflict (client_account_id,workspace_membership_id) do nothing",
			uuid(account), uuid(membership), uuid(tenant), uuid(workspace), timestamp(epoch));
		if (inserted != 1) return 0;
		return jdbc.update("update sales.client_account set updated_at=current_timestamp,version=version+1 "
				+ "where tenant_id=? and workspace_id=? and id=? and version=?",
			uuid(tenant), uuid(workspace), uuid(account), version);
	}
	private String accountSql() { return "select a.id,a.code,a.business_name,a.commercial_name,a.tax_country_code,a.tax_identifier_type,a.tax_identifier_value,a.segment,a.contact_person,a.contact_email,a.phone,a.delivery_profile,a.payment_condition,a.status,cam.workspace_membership_id,a.version,a.account_type,a.sales_owner_membership_id from sales.client_account a left join lateral (select member.workspace_membership_id from sales.client_account_membership member where member.client_account_id=a.id and member.tenant_id=a.tenant_id and member.workspace_id=a.workspace_id order by member.created_at,member.workspace_membership_id limit 1) cam on true"; }
	private String accountSqlForMembership() {
		return "select a.id,a.code,a.business_name,a.commercial_name,a.tax_country_code,a.tax_identifier_type,"
				+ "a.tax_identifier_value,a.segment,"
				// Retained inactive links still count: exposing shared contact fields after deactivation would
				// disclose the previous member's personal data to the remaining Buyer.
				+ "case when account_membership_count.membership_count=1 then a.contact_person end,"
				+ "case when account_membership_count.membership_count=1 then a.contact_email end,"
				+ "case when account_membership_count.membership_count=1 then a.phone end,"
				+ "a.delivery_profile,a.payment_condition,a.status,cam.workspace_membership_id,a.version,a.account_type,"
				+ "a.sales_owner_membership_id from sales.client_account a left join lateral "
				+ "(select count(*) as membership_count from sales.client_account_membership member_count "
				+ "where member_count.tenant_id=a.tenant_id and member_count.workspace_id=a.workspace_id "
				+ "and member_count.client_account_id=a.id) account_membership_count on true "
				+ "left join sales.client_account_membership cam "
				+ "on cam.client_account_id=a.id and cam.tenant_id=a.tenant_id and cam.workspace_id=a.workspace_id "
				+ "and cam.workspace_membership_id=?";
	}
	private ClientAccountView account(java.sql.ResultSet rs) throws java.sql.SQLException { return new ClientAccountView(rs.getObject(1).toString(),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11),rs.getString(12),rs.getString(13),rs.getString(14),rs.getObject(15) == null ? null : rs.getObject(15).toString(),rs.getLong(16), com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType.valueOf(rs.getString(17)),rs.getObject(18) == null ? null : rs.getObject(18).toString()); }
	private Optional<ClientAccountView> optionalAccount(java.sql.ResultSet rs) throws java.sql.SQLException { return rs.next() ? Optional.of(account(rs)) : Optional.empty(); }
	private Optional<CustomerAccountDetails> details(String tenant, String workspace, String predicate, Object value) {
		return jdbc.query("select a.id,a.code,a.business_name,a.commercial_name,a.tax_identifier_type,a.tax_identifier_value,"
				+ "a.segment,a.status from sales.client_account a where a.tenant_id=? and a.workspace_id=? and "
				+ predicate,
				(rs, ignored) -> new CustomerAccountDetails(rs.getObject(1).toString(), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)),
				uuid(tenant), uuid(workspace), value).stream().findFirst();
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
	private static Timestamp timestamp(long epoch) { return Timestamp.from(Instant.ofEpochMilli(epoch)); }
	private record BuyerMembershipPageRow(long accountVersion, long total, ClientAccountBuyerMembershipView membership) { }
}
