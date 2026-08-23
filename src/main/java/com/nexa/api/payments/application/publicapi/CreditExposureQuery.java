package com.nexa.api.payments.application.publicapi;

import java.math.BigDecimal;

/** Read-only Credit & Receivables exposure owned by Payments. */
public interface CreditExposureQuery {
    CreditExposureSnapshot find(String tenantId, String workspaceId, String customerAccountId, String currency);

    record CreditExposureSnapshot(BigDecimal outstandingReceivables, BigDecimal reservedExposure) {
        public CreditExposureSnapshot {
            outstandingReceivables = value(outstandingReceivables);
            reservedExposure = value(reservedExposure);
        }

        public BigDecimal used() {
            return outstandingReceivables.add(reservedExposure);
        }

        private static BigDecimal value(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
