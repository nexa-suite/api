package com.nexa.api.creditreceivables.application.publicapi;

import java.math.BigDecimal;
import java.util.UUID;

/** Read-only projection for one Tenant-scoped customer credit configuration. */
public interface CreditAccountConfigurationQuery {
    Snapshot find(UUID tenantId, UUID workspaceId, UUID customerAccountId, String currency);

    enum Status { NOT_CONFIGURED, ACTIVE, SUSPENDED, CLOSED }

    record Snapshot(UUID customerAccountId, String currency, Status status, BigDecimal creditLimit,
                    BigDecimal financedExposure, BigDecimal outstandingReceivables,
                    BigDecimal reservedExposure, Long version) {
        public Snapshot {
            if (customerAccountId == null || currency == null || !currency.matches("[A-Z]{3}") || status == null) {
                throw new IllegalArgumentException("Credit configuration snapshot scope is incomplete");
            }
            boolean configured = status != Status.NOT_CONFIGURED;
            boolean allValuesPresent = creditLimit != null && financedExposure != null
                    && outstandingReceivables != null && reservedExposure != null && version != null;
            boolean allValuesAbsent = creditLimit == null && financedExposure == null
                    && outstandingReceivables == null && reservedExposure == null && version == null;
            if (configured ? !allValuesPresent : !allValuesAbsent) {
                throw new IllegalArgumentException("Credit configuration snapshot state is inconsistent");
            }
            if (configured && (creditLimit.signum() < 0 || financedExposure.signum() < 0
                    || outstandingReceivables.signum() < 0 || reservedExposure.signum() < 0)) {
                throw new IllegalArgumentException("Credit configuration snapshot amounts cannot be negative");
            }
            if (version != null && version < 0) {
                throw new IllegalArgumentException("Credit configuration version cannot be negative");
            }
        }

        public BigDecimal used() {
            if (status == Status.NOT_CONFIGURED) return null;
            return financedExposure.add(outstandingReceivables).add(reservedExposure);
        }

        public BigDecimal availableCredit() {
            if (status == Status.NOT_CONFIGURED) return null;
            return creditLimit.subtract(used()).max(BigDecimal.ZERO);
        }

        public static Snapshot notConfigured(UUID customerAccountId, String currency) {
            return new Snapshot(customerAccountId, currency, Status.NOT_CONFIGURED,
                    null, null, null, null, null);
        }
    }
}
