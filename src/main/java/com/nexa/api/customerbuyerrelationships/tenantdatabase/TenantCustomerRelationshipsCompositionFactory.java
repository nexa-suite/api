package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory.BuyerMembershipReference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory.SalesMembershipReference;
import com.nexa.api.businesstraceability.application.publicapi.BusinessTraceabilityCommands;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Builds BC-02 request use cases against the exact JDBC session supplied by Tenant routing. */
@FunctionalInterface
public interface TenantCustomerRelationshipsCompositionFactory {
    Composition bindTo(JdbcTemplate tenantJdbc, List<BuyerMembershipReference> buyerMembershipSnapshot,
                       List<SalesMembershipReference> activeSalesMembershipSnapshot,
                       BusinessTraceabilityCommands traceability);

    record Composition(ClientAccountUseCase clientAccounts, ClientAccountAddressUseCase addresses,
                       BuyerAddressUseCase buyerAddresses, FieldVisitUseCase fieldVisits) {
        public Composition {
            Objects.requireNonNull(clientAccounts, "Tenant client-account use case is required");
            Objects.requireNonNull(addresses, "Tenant address use case is required");
            Objects.requireNonNull(buyerAddresses, "Tenant Buyer personal-address use case is required");
            Objects.requireNonNull(fieldVisits, "Tenant field-visit use case is required");
        }
    }
}
