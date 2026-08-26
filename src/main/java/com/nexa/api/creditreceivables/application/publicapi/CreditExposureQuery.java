package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;

/** Read-only Credit & Receivables exposure owned by Payments. */
public interface CreditExposureQuery {
    CreditExposureSnapshot find(String tenantId, String workspaceId, String customerAccountId, String currency);

    record CreditExposureSnapshot(String currency, BigDecimal creditLimit, BigDecimal ledgerExposure,
                                  BigDecimal outstandingReceivables, BigDecimal reservedExposure, boolean active) {
        public CreditExposureSnapshot {
            currency = currency == null || currency.isBlank() ? "PEN" : currency.trim().toUpperCase(java.util.Locale.ROOT);
            creditLimit = value(creditLimit);
            ledgerExposure = value(ledgerExposure);
            outstandingReceivables = value(outstandingReceivables);
            reservedExposure = value(reservedExposure);
        }

        public BigDecimal used() {
            return ledgerExposure.add(outstandingReceivables).add(reservedExposure);
        }

        public BigDecimal availableCredit() {
            return creditLimit.subtract(used()).max(BigDecimal.ZERO);
        }

        public static CreditExposureSnapshot unavailable(String currency) {
            return new CreditExposureSnapshot(currency, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, false);
        }

        private static BigDecimal value(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
