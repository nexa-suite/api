package com.nexa.api.sales.infrastructure.clientaccount;

import com.nexa.api.sales.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.sales.domain.model.commercial.PaymentTerms;
import com.nexa.api.sales.domain.model.credit.CreditProfile;
import com.nexa.api.sales.domain.model.credit.CreditStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/** ACL adapter: only commercial facts cross from Client Account into Sales. */
@Repository
@Profile("!test")
public class ClientAccountCommercialAclAdapter implements ClientAccountCommercialPort {
    private static final String SELECT = "select a.id,a.business_name,a.commercial_name,a.tax_identifier_value,"
            + "a.credit_limit,"
            + "coalesce((select sum(r.amount-r.amount_paid) from payments.receivable r "
            + "where r.tenant_id=a.tenant_id and r.workspace_id=a.workspace_id "
            + "and r.client_account_id=a.id and r.currency=a.credit_currency "
            + "and r.status in ('OPEN','PARTIALLY_PAID','OVERDUE')),0) "
            + "+ coalesce((select sum(ca.reserved_exposure) from payments.credit_account ca "
            + "where ca.tenant_id=a.tenant_id and ca.workspace_id=a.workspace_id "
            + "and ca.client_account_id=a.id and ca.currency=a.credit_currency and ca.status='ACTIVE'),0),"
            + "greatest(a.credit_limit - ("
            + "coalesce((select sum(r2.amount-r2.amount_paid) from payments.receivable r2 "
            + "where r2.tenant_id=a.tenant_id and r2.workspace_id=a.workspace_id "
            + "and r2.client_account_id=a.id and r2.currency=a.credit_currency "
            + "and r2.status in ('OPEN','PARTIALLY_PAID','OVERDUE')),0) "
            + "+ coalesce((select sum(ca2.reserved_exposure) from payments.credit_account ca2 "
            + "where ca2.tenant_id=a.tenant_id and ca2.workspace_id=a.workspace_id "
            + "and ca2.client_account_id=a.id and ca2.currency=a.credit_currency and ca2.status='ACTIVE'),0)),0),"
            + "a.payment_condition,a.status "
            + "from sales.client_account a";
    private final JdbcTemplate jdbc;

    public ClientAccountCommercialAclAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<ClientAccountCommercialProfile> find(String tenantId, String workspaceId, String clientAccountId) {
        return jdbc.query(SELECT + " where a.tenant_id=? and a.workspace_id=? and a.id=?",
                rs -> rs.next() ? Optional.of(profile(rs)) : Optional.empty(), uuid(tenantId), uuid(workspaceId), uuid(clientAccountId));
    }

    @Override
    public Optional<ClientAccountCommercialProfile> findForBuyer(String tenantId, String workspaceId, String membershipId) {
        return jdbc.query(SELECT + " join sales.client_account_membership cam on cam.client_account_id=a.id "
                        + "and cam.tenant_id=a.tenant_id and cam.workspace_id=a.workspace_id "
                        + "where a.tenant_id=? and a.workspace_id=? and cam.workspace_membership_id=? and a.status='ACTIVE'",
                rs -> rs.next() ? Optional.of(profile(rs)) : Optional.empty(),
                uuid(tenantId), uuid(workspaceId), uuid(membershipId));
    }

    private ClientAccountCommercialProfile profile(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal limit = value(rs.getBigDecimal(5));
        BigDecimal used = value(rs.getBigDecimal(6));
        boolean active = "ACTIVE".equalsIgnoreCase(rs.getString(9));
        CreditStatus status = active ? CreditStatus.AVAILABLE : CreditStatus.BLOCKED;
        String condition = rs.getString(8);
        return new ClientAccountCommercialProfile(rs.getObject(1).toString(), rs.getString(2), rs.getString(3),
                rs.getString(4), new CreditProfile(limit, used, status), paymentTerms(condition), active);
    }

    private static PaymentTerms paymentTerms(String condition) {
        String code = condition == null || condition.isBlank() ? "CASH" : condition.trim();
        boolean credit = code.toUpperCase(java.util.Locale.ROOT).contains("CREDIT")
                || code.toUpperCase(java.util.Locale.ROOT).matches(".*NET[-_ ]?\\d+.*");
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)").matcher(code);
        int dueDays = matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        return new PaymentTerms(code, code, credit ? dueDays : 0, credit);
    }

    private static BigDecimal value(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static UUID uuid(String value) { return UUID.fromString(value); }
}
