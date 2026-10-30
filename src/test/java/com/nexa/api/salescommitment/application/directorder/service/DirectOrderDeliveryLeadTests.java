package com.nexa.api.salescommitment.application.directorder.service;

import com.nexa.api.catalogcommercialpolicy.application.publicapi.SellableSkuQuery;
import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.salescommitment.application.directorder.port.DirectOrderUseCase;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.SalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DirectOrderDeliveryLeadTests {
    @Test
    void internalDirectOrderRejectsTooSoonDateBeforeCatalogOrCommitmentWork() {
        CommercialCommitmentPort commitments = mock(CommercialCommitmentPort.class);
        SalesOrderPersistencePort orders = mock(SalesOrderPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        SellableSkuQuery sellableSkus = mock(SellableSkuQuery.class);
        when(idempotency.find(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        DirectOrderService service = new DirectOrderService(commitments, orders,
                Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC), idempotency, null, sellableSkus);

        assertThatThrownBy(() -> service.create(SalesTestFixtures.salesContext(), UUID.randomUUID().toString(),
                "NORMAL", LocalDate.of(2026, 11, 4), null, "IMMEDIATE", null,
                List.of(new DirectOrderUseCase.Line("CAT-0002", BigDecimal.ONE, "UNIT")), "direct-order-1"))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verifyNoInteractions(commitments, orders, sellableSkus);
    }

    @Test
    void previouslyCommittedDirectOrderStillReplaysAfterItsDateFallsInsideTheCurrentLeadWindow() {
        CommercialCommitmentPort commitments = mock(CommercialCommitmentPort.class);
        SalesOrderPersistencePort orders = mock(SalesOrderPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        SellableSkuQuery sellableSkus = mock(SellableSkuQuery.class);
        when(idempotency.find(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.of(new IdempotencyPersistencePort.IdempotencyResult(
                        "existing-order", 1, "{\"id\":\"existing-order\",\"version\":1,\"lines\":[]}")));
        var mapper = new tools.jackson.databind.ObjectMapper();
        DirectOrderService service = new DirectOrderService(commitments, orders,
                Clock.fixed(Instant.parse("2026-10-31T17:00:00Z"), ZoneOffset.UTC), idempotency, mapper, sellableSkus);

        SalesOrderView replay = service.create(SalesTestFixtures.salesContext(), UUID.randomUUID().toString(),
                "NORMAL", LocalDate.of(2026, 11, 5), null, "IMMEDIATE", null,
                List.of(new DirectOrderUseCase.Line("CAT-0002", BigDecimal.ONE, "UNIT")), "direct-order-1");

        org.assertj.core.api.Assertions.assertThat(replay.id()).isEqualTo("existing-order");
        verifyNoInteractions(commitments, orders, sellableSkus);
    }
}
