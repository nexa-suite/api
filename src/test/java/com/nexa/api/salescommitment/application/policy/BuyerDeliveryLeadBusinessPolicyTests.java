package com.nexa.api.salescommitment.application.policy;

import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;
import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuyerDeliveryLeadBusinessPolicyTests {
    private final BuyerDeliveryLeadBusinessPolicy policy = new BuyerDeliveryLeadBusinessPolicy();

    @Test
    void calculatesFromTheCurrentDateInLimaAndStartsTheNextDay() {
        Clock beforeLimaMidnight = Clock.fixed(Instant.parse("2026-10-30T04:30:00Z"), ZoneOffset.UTC);
        Clock afterLimaMidnight = Clock.fixed(Instant.parse("2026-10-30T05:30:00Z"), ZoneOffset.UTC);

        assertThat(policy.minimumAllowedDeliveryDate(beforeLimaMidnight)).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(policy.minimumAllowedDeliveryDate(afterLimaMidnight)).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void fiveBusinessDaysCanSpanSevenCalendarDaysFromMonday() {
        Clock mondayAtLimaNoon = Clock.fixed(Instant.parse("2026-10-26T17:00:00Z"), ZoneOffset.UTC);

        assertThat(policy.minimumAllowedDeliveryDate(mondayAtLimaNoon)).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void countsAcrossLeapDayAndYearBoundary() {
        Clock beforeLeapDay = Clock.fixed(Instant.parse("2024-02-22T17:00:00Z"), ZoneOffset.UTC);
        Clock beforeYearBoundary = Clock.fixed(Instant.parse("2026-12-28T17:00:00Z"), ZoneOffset.UTC);

        assertThat(policy.minimumAllowedDeliveryDate(beforeLeapDay)).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(policy.minimumAllowedDeliveryDate(beforeYearBoundary)).isEqualTo(LocalDate.of(2027, 1, 4));
    }

    @Test
    void countsMondayThroughFridayAndSkipsWeekendDays() {
        Clock fridayAtLimaNoon = Clock.fixed(Instant.parse("2026-10-30T17:00:00Z"), ZoneOffset.UTC);

        assertThat(policy.minimumAllowedDeliveryDate(fridayAtLimaNoon)).isEqualTo(LocalDate.of(2026, 11, 6));
    }

    @Test
    void countsFromEveryReferenceDayAndAcceptsAWeekendAfterTheMinimum() {
        LocalDate monday = LocalDate.of(2026, 10, 26);
        for (int offset = 0; offset < 7; offset++) {
            LocalDate reference = monday.plusDays(offset);
            Clock clock = Clock.fixed(reference.atTime(12, 0)
                    .atZone(java.time.ZoneId.of("America/Lima")).toInstant(), ZoneOffset.UTC);
            LocalDate expected = offset < 5 ? reference.plusDays(7) : monday.plusDays(11);
            assertThat(policy.minimumAllowedDeliveryDate(clock)).as(reference.toString()).isEqualTo(expected);
        }
        Clock mondayClock = Clock.fixed(Instant.parse("2026-10-26T17:00:00Z"), ZoneOffset.UTC);
        assertThatCode(() -> policy.validate(LocalDate.of(2026, 11, 7), mondayClock))
                .doesNotThrowAnyException();
    }

    @Test
    void countsWeekdaysWithoutConsultingAHolidayCalendar() {
        Clock beforePeruNationalHolidays = Clock.fixed(Instant.parse("2026-07-23T17:00:00Z"), ZoneOffset.UTC);

        assertThat(policy.minimumAllowedDeliveryDate(beforePeruNationalHolidays)).isEqualTo(LocalDate.of(2026, 7, 30));
    }

    @Test
    void acceptsEarliestDateAndRejectsTheDayBeforeIt() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC);
        LocalDate earliest = policy.minimumAllowedDeliveryDate(clock);

        assertThatCode(() -> policy.validate(earliest, clock)).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(earliest.minusDays(1), clock))
                .isInstanceOfSatisfying(MinimumDeliveryLeadBusinessDaysViolationException.class, violation -> {
                    assertThat(violation.requestedDate()).isEqualTo(earliest.minusDays(1));
                    assertThat(violation.minimumAllowedDate()).isEqualTo(earliest);
                });
    }

    @Test
    void queryReturnsFixedGlobalPolicyOnlyForCurrentBuyerPortalContext() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC);
        BuyerDeliveryLeadPolicyService service = new BuyerDeliveryLeadPolicyService(clock);

        var result = service.get(SalesTestFixtures.buyerContext());

        assertThat(result.minimumDeliveryLeadBusinessDays()).isEqualTo(5);
        assertThat(result.minimumAllowedDeliveryDate()).isEqualTo(LocalDate.of(2026, 11, 5));
        assertThat(result.timezone()).isEqualTo("America/Lima");
        assertThatThrownBy(() -> service.get(SalesTestFixtures.salesContext()))
                .isInstanceOf(AccessPolicyViolation.class);
    }
}
