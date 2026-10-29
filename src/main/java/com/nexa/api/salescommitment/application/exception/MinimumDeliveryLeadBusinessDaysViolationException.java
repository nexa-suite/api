package com.nexa.api.salescommitment.application.exception;

import java.time.LocalDate;
import java.util.Objects;

public final class MinimumDeliveryLeadBusinessDaysViolationException extends RuntimeException {
    private final LocalDate requestedDate;
    private final LocalDate minimumAllowedDate;

    public MinimumDeliveryLeadBusinessDaysViolationException(LocalDate requestedDate, LocalDate minimumAllowedDate) {
        super("Requested delivery date is earlier than the minimum allowed date");
        this.requestedDate = Objects.requireNonNull(requestedDate, "Requested date is required");
        this.minimumAllowedDate = Objects.requireNonNull(minimumAllowedDate, "Minimum allowed date is required");
    }

    public LocalDate requestedDate() {
        return requestedDate;
    }

    public LocalDate minimumAllowedDate() {
        return minimumAllowedDate;
    }
}
