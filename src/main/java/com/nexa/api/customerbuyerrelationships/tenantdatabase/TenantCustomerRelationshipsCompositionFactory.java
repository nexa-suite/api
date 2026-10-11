package com.nexa.api.customerbuyerrelationships.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory.BuyerMembershipReference;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/** Builds BC-02 request use cases against the exact JDBC session supplied by Tenant routing. */
@FunctionalInterface
public interface TenantCustomerRelationshipsCompositionFactory {
    Composition bindTo(JdbcTemplate tenantJdbc, List<BuyerMembershipReference> buyerMembershipSnapshot);

    record Composition(ClientAccountUseCase clientAccounts, ClientAccountAddressUseCase addresses,
                       FieldVisitUseCase fieldVisits) {
        public Composition {
            Objects.requireNonNull(clientAccounts, "Tenant client-account use case is required");
            Objects.requireNonNull(addresses, "Tenant address use case is required");
            Objects.requireNonNull(fieldVisits, "Tenant field-visit use case is required");
        }
    }
}
