package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/** Fixed global delivery lead rule: five weekdays after the current Lima date. */
public final class BuyerDeliveryLeadBusinessPolicy {
    public static final int MINIMUM_DELIVERY_LEAD_BUSINESS_DAYS = 5;
    public static final String TIMEZONE = "America/Lima";

    private static final ZoneId BUSINESS_ZONE = ZoneId.of(TIMEZONE);

    public LocalDate minimumAllowedDeliveryDate(Clock clock) {
        LocalDate candidate = LocalDate.now(Objects.requireNonNull(clock, "Clock is required").withZone(BUSINESS_ZONE));
        int businessDays = 0;
        while (businessDays < MINIMUM_DELIVERY_LEAD_BUSINESS_DAYS) {
            candidate = candidate.plusDays(1);
            if (isBusinessDay(candidate)) businessDays++;
        }
        return candidate;
    }

    public void validate(LocalDate requestedDate, Clock clock) {
        if (requestedDate == null) throw new IllegalArgumentException("Requested delivery date is required");
        LocalDate earliest = minimumAllowedDeliveryDate(clock);
        if (requestedDate.isBefore(earliest)) {
            throw new MinimumDeliveryLeadBusinessDaysViolationException(requestedDate, earliest);
        }
    }

    private static boolean isBusinessDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
