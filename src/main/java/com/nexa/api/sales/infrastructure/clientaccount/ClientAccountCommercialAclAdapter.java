package com.nexa.api.sales.infrastructure.clientaccount;

import com.nexa.api.sales.application.port.out.ClientAccountCommercialPort;
import com.nexa.api.sales.domain.model.commercial.PaymentTerms;
import com.nexa.api.sales.domain.model.credit.CreditProfile;
import com.nexa.api.sales.domain.model.credit.CreditStatus;
import com.nexa.api.catalogmanagement.application.publicapi.CustomerTermsQuery;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountDetails;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.CreditExposureQuery;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** ACL adapter: only commercial facts cross from Client Account into Sales. */
@Component
@Profile("!test")
public class ClientAccountCommercialAclAdapter implements ClientAccountCommercialPort {
    private final CustomerAccountQuery customers;
    private final CustomerTermsQuery terms;
    private final CreditExposureQuery credit;

    public ClientAccountCommercialAclAdapter(
            CustomerAccountQuery customers, CustomerTermsQuery terms, CreditExposureQuery credit) {
        this.customers = customers;
        this.terms = terms;
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
        var customerTerms = terms.findTerms(tenantId, workspaceId, customer.id())
                .orElseThrow(() -> new IllegalStateException("Customer commercial terms are not configured"));
        var exposure = credit.find(tenantId, workspaceId, customer.id(), "PEN");
        return new ClientAccountCommercialProfile(customer.id(), customer.businessName(), customer.commercialName(),
                customer.taxIdentifierValue(), new CreditProfile(exposure.creditLimit(), exposure.used(), CreditStatus.AVAILABLE),
                new PaymentTerms(customerTerms.code(), customerTerms.description(), customerTerms.dueDays(),
                        customerTerms.creditAllowed()), true);
    }
}
