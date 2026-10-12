package com.nexa.api.salescommitment.infrastructure.commitment;

import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.tenantdatabase.TenantCommercialCommitmentFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Creates the existing BC-04 adapter on the caller's exact Tenant JDBC session. */
@Component
@Profile("!test")
public final class JdbcTenantCommercialCommitmentFactory implements TenantCommercialCommitmentFactory {
    @Override
    public CommercialCommitmentPort bindTo(JdbcTemplate tenantJdbc, Bindings bindings) {
        Objects.requireNonNull(bindings, "Tenant commitment bindings are required");
        return new CommercialCommitmentPersistenceAdapter(
                Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required"),
                bindings.sellableSkus(), bindings.customerAccounts(), bindings.creditReservations(),
                bindings.inventoryBacking(), bindings.paymentConfirmations(), bindings.receivables(),
                bindings.clock(), bindings.canonicalOutbox());
    }
}
