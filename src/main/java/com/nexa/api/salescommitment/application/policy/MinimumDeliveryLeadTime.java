package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadTimeViolationException;
import org.springframework.modulith.NamedInterface;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** Workspace policy for the earliest date a new or changed order may request. */
@NamedInterface(value = "sales-public", propagate = false)
public record MinimumDeliveryLeadTime(int calendarDays) {
    public MinimumDeliveryLeadTime {
        if (calendarDays < 0) {
            throw new IllegalArgumentException("Minimum delivery lead time cannot be negative");
        }
    }

    public LocalDate earliestAllowedDate(Clock clock) {
        Objects.requireNonNull(clock, "Clock is required");
        try {
            return LocalDate.now(clock).plusDays(calendarDays);
        } catch (java.time.DateTimeException exception) {
            throw new IllegalArgumentException("Minimum delivery lead time is outside the supported date range", exception);
        }
    }

    /** Null remains accepted only for legacy contracts that make a requested date optional. */
    public void validate(LocalDate requestedDate, Clock clock) {
        if (requestedDate == null) return;
        LocalDate earliest = earliestAllowedDate(clock);
        if (requestedDate.isBefore(earliest)) {
            throw new MinimumDeliveryLeadTimeViolationException(requestedDate, earliest, calendarDays);
        }
    }
}
