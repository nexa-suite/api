package com.nexa.api.salescommitment.application.exception;

import java.time.LocalDate;
import java.util.Objects;

public final class MinimumDeliveryLeadTimeViolationException extends RuntimeException {
    private final LocalDate requestedDate;
    private final LocalDate earliestAllowedDate;
    private final int minimumCalendarDays;

    public MinimumDeliveryLeadTimeViolationException(LocalDate requestedDate, LocalDate earliestAllowedDate,
            int minimumCalendarDays) {
        super("Requested delivery date is earlier than the workspace minimum lead time");
        this.requestedDate = Objects.requireNonNull(requestedDate);
        this.earliestAllowedDate = Objects.requireNonNull(earliestAllowedDate);
        this.minimumCalendarDays = minimumCalendarDays;
    }

    public LocalDate requestedDate() { return requestedDate; }
    public LocalDate earliestAllowedDate() { return earliestAllowedDate; }
    public int minimumCalendarDays() { return minimumCalendarDays; }
}
