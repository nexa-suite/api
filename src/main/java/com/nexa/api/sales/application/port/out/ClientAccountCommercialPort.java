package com.nexa.api.sales.application.port.out;

import com.nexa.api.sales.domain.model.commercial.PaymentTerms;
import com.nexa.api.sales.domain.model.credit.CreditProfile;

import java.util.Optional;

/** Narrow Sales ACL for ACTIVE customer commercial facts needed by order workflows. */
public interface ClientAccountCommercialPort {
    Optional<ClientAccountCommercialProfile> find(String tenantId, String workspaceId, String clientAccountId);

    Optional<ClientAccountCommercialProfile> findForBuyer(String tenantId, String workspaceId, String membershipId);

    record ClientAccountCommercialProfile(String id, String businessName, String commercialName,
                                           String taxIdentifier, CreditProfile credit, PaymentTerms paymentTerms,
                                           boolean active) { }
}
