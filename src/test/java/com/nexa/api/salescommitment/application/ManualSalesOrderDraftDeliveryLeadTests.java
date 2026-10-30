package com.nexa.api.salescommitment.application;

import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderDraftModels;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderDraftPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.salescommitment.application.salesorder.port.ManualSalesOrderUseCase;
import com.nexa.api.salescommitment.application.salesorder.service.ManualSalesOrderDraftService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ManualSalesOrderDraftDeliveryLeadTests {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T17:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TOO_EARLY = LocalDate.of(2026, 11, 4);

    @Test
    void manualSalesDraftDateChangeRejectsBeforePersistence() {
        ManualSalesOrderDraftPersistencePort drafts = mock(ManualSalesOrderDraftPersistencePort.class);
        ManualSalesOrderDraftService service = new ManualSalesOrderDraftService(drafts,
                mock(ManualSalesOrderUseCase.class), mock(ManualSalesOrderPersistencePort.class), CLOCK);
        var command = new ManualSalesOrderDraftModels.ClientCommand(UUID.randomUUID(), TOO_EARLY,
                "NORMAL", "CASH", "PEN", null);

        assertThatThrownBy(() -> service.updateClient(SalesTestFixtures.salesContext(), UUID.randomUUID(), 0, command))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verifyNoInteractions(drafts);
    }

    @Test
    void manualSalesDraftSubmitRevalidatesBeforeCreatingOrder() {
        ManualSalesOrderDraftPersistencePort drafts = mock(ManualSalesOrderDraftPersistencePort.class);
        ManualSalesOrderUseCase manualOrders = mock(ManualSalesOrderUseCase.class);
        ManualSalesOrderPersistencePort orders = mock(ManualSalesOrderPersistencePort.class);
        ManualSalesOrderDraftService service = new ManualSalesOrderDraftService(drafts, manualOrders, orders, CLOCK);
        UUID draftId = UUID.randomUUID();
        var draft = new ManualSalesOrderDraftModels.DraftView(draftId.toString(), "READY_TO_CREATE", 0,
                null, TOO_EARLY, "NORMAL", "CASH", "PEN", null, null, List.of(), null,
                true, null, Instant.EPOCH, Instant.EPOCH, null);
        when(drafts.getForUpdate(any(), eq(draftId))).thenReturn(draft);

        assertThatThrownBy(() -> service.submit(SalesTestFixtures.salesContext(), draftId, 0, "manual-submit-1"))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verify(drafts).getForUpdate(any(), eq(draftId));
        verifyNoInteractions(manualOrders, orders);
    }
}
