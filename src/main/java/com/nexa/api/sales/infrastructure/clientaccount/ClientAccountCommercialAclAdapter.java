package com.nexa.api.sales.infrastructure.clientaccount;

import com.nexa.api.sales.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.sales.domain.model.commercial.PaymentTerms;
import com.nexa.api.sales.domain.model.credit.CreditProfile;
import com.nexa.api.sales.domain.model.credit.CreditStatus;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.CreditExposureQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/** ACL adapter: only commercial facts cross from Client Account into Sales. */
@Component
@Profile("!test")
public class ClientAccountCommercialAclAdapter implements ClientAccountCommercialPort {
    private final CustomerAccountQuery customers;
    private final CreditExposureQuery credit;

    public ClientAccountCommercialAclAdapter(CustomerAccountQuery customers, CreditExposureQuery credit) {
        this.customers = customers;
        this.credit = credit;
    }

    @Override
    public Optional<ClientAccountCommercialProfile> find(String tenantId, String workspaceId, String clientAccountId) {
        return customers.findActiveDetails(tenantId, workspaceId, clientAccountId)
                .map(value -> profile(tenantId, workspaceId, value));
    }

    @Override
    public Optional<ClientAccountCommercialProfile> findForBuyer(String tenantId, String workspaceId, String membershipId) {
        return customers.findActiveBuyerDetails(tenantId, workspaceId, membershipId)
                .map(value -> profile(tenantId, workspaceId, value));
    }

    private ClientAccountCommercialProfile profile(String tenantId, String workspaceId, CustomerAccountDetails customer) {
        BigDecimal limit = value(customer.creditLimit());
        BigDecimal used = credit.find(tenantId, workspaceId, customer.id(), customer.creditCurrency()).used();
        return new ClientAccountCommercialProfile(customer.id(), customer.businessName(), customer.commercialName(),
                customer.taxIdentifierValue(), new CreditProfile(limit, used, CreditStatus.AVAILABLE),
                paymentTerms(customer.paymentCondition()), true);
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
}
