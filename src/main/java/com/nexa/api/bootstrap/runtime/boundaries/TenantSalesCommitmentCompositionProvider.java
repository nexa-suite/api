package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.salescommitment.tenantdatabase.TenantSalesCommitmentCompositionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/** Builds owner-bound BC-04 use cases only from the current router callback session. */
@FunctionalInterface
public interface TenantSalesCommitmentCompositionProvider {
    TenantSalesCommitmentCompositionFactory.Composition bindTo(JdbcTemplate tenantJdbc);

    /** True only when the Tenant PR command composition is explicitly configured for wallet tender. */
    default boolean walletOrderPaymentEnabled() { return false; }
}
