package com.nexa.api.salescommitment.infrastructure.purchaserequestdraft;

import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class BuyerDeliveryLeadDraftTests {
    @Test
    void buyerDraftCreationRejectsDateBeforeGlobalMinimumWithoutWriting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC);
        PurchaseRequestDraftService service = new PurchaseRequestDraftService(jdbc, null, null, null, null, null,
                null, null, null, null, false, clock);

        assertThatThrownBy(() -> service.create(SalesTestFixtures.buyerContext(), UUID.randomUUID(),
                LocalDate.of(2026, 11, 4)))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verifyNoInteractions(jdbc);
    }
}
