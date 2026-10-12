package com.nexa.api.creditreceivables.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.math.BigDecimal;
import java.util.UUID;

/** Authorized BC-07 HTTP operation for immutable post-payment corrections. */
public interface FinancialAdjustmentUseCase {
    FinancialAdjustmentCommands.Result postPostPayment(CurrentAccessContext context, UUID receivableId,
            long expectedReceivableVersion, String idempotencyKey, PostPaymentCommand command);

    record PostPaymentCommand(UUID salesOrderId, UUID sourceId, String sourceType,
                              String adjustmentKind, String effect, BigDecimal amount,
                              String currency, String reason, String obligationType) {
        public PostPaymentCommand {
            if (salesOrderId == null || sourceId == null || sourceType == null || sourceType.isBlank()
                    || amount == null || amount.signum() <= 0 || currency == null || currency.isBlank()
                    || reason == null || reason.isBlank() || reason.length() > 2000) {
                throw new IllegalArgumentException("Post-payment adjustment request is incomplete");
            }
        }
    }
}
