package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.creditreceivables.application.publicapi.ReceivablePaymentAccess;
import com.nexa.api.payments.application.publicapi.PaymentDocumentSourceQuery;
import com.nexa.api.shared.events.PaymentEventContextQueryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Composes immutable event-recipient facts from the Payment and Receivable owners. */
@Component
@Profile("!test")
public class PaymentEventContextCompositionAdapter implements PaymentEventContextQueryPort {
    private final ReceivablePaymentAccess receivables;
    private final PaymentDocumentSourceQuery payments;

    public PaymentEventContextCompositionAdapter(ReceivablePaymentAccess receivables,
                                                 PaymentDocumentSourceQuery payments) {
        this.receivables = receivables;
        this.payments = payments;
    }

    @Override
    public Optional<UUID> findClientAccountId(UUID tenantId, UUID workspaceId, String aggregateType,
                                               UUID aggregateId) {
        if (aggregateType == null || aggregateId == null) return Optional.empty();
        return switch (aggregateType) {
            case "Receivable" -> receivables.find(tenantId, workspaceId, aggregateId)
                    .map(ReceivablePaymentAccess.Snapshot::clientAccountId);
            case "Payment" -> payments.find(tenantId, workspaceId, aggregateId)
                    .map(PaymentDocumentSourceQuery.Snapshot::customerAccountId);
            default -> Optional.empty();
        };
    }
}
