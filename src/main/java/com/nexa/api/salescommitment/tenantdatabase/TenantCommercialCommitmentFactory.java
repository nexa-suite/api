package com.nexa.api.salescommitment.tenantdatabase;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.creditreceivables.application.publicapi.CreditReservationCommands;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryBackingCommands;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

/** Binds BC-04 commitment persistence and its owner ports to one Tenant transaction. */
@FunctionalInterface
public interface TenantCommercialCommitmentFactory {
    CommercialCommitmentPort bindTo(JdbcTemplate tenantJdbc, Bindings bindings);

    record Bindings(SellableSkuQuery sellableSkus, CustomerAccountQuery customerAccounts,
                    CreditReservationCommands creditReservations,
                    InventoryBackingCommands inventoryBacking,
                    PaymentConfirmationQuery paymentConfirmations,
                    com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands receivables,
                    Clock clock, CanonicalOutboxPort canonicalOutbox) {
        public Bindings {
            if (sellableSkus == null || customerAccounts == null || creditReservations == null
                    || inventoryBacking == null || clock == null || canonicalOutbox == null) {
                throw new IllegalArgumentException("Tenant commitment bindings are incomplete");
            }
        }
    }
}
