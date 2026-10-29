package com.nexa.api.salescommitment.application.purchaserequest.service;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.application.exception.MinimumDeliveryLeadBusinessDaysViolationException;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.salescommitment.application.purchaserequest.model.PurchaseRequestView;
import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.salescommitment.application.purchaserequest.port.IdempotencyPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestEventPersistencePort;
import com.nexa.api.salescommitment.application.purchaserequest.port.PurchaseRequestPersistencePort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuyerDeliveryLeadValidationTests {
    private static final String REQUEST_ID = "4b3a5b3c-80cf-41ec-8b3d-1f1d2c4e5f60";
    private static final Instant NOW = Instant.parse("2026-10-29T17:00:00Z");
    private static final LocalDate TOO_EARLY = LocalDate.of(2026, 11, 4);

    @Test
    void purchaseRequestDraftCreationRejectsDateBeforeGlobalMinimum() {
        PurchaseRequestPersistencePort persistence = mock(PurchaseRequestPersistencePort.class);
        PurchaseRequestEventPersistencePort events = mock(PurchaseRequestEventPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        CatalogItemSnapshotLookupPort catalog = mock(CatalogItemSnapshotLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        ChangeEventPersistencePort changeFeed = mock(ChangeEventPersistencePort.class);
        PurchaseRequestService service = new PurchaseRequestService(persistence, events, idempotency, catalog,
                accounts, changeFeed, null, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.create(SalesTestFixtures.buyerContext(), null, "NORMAL", TOO_EARLY,
                "delivery", "IMMEDIATE", "comment", List.of()))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verifyNoInteractions(persistence, events, idempotency, catalog, accounts, changeFeed);
    }

    @Test
    void purchaseRequestSubmissionRevalidatesStoredDeliveryDate() {
        PurchaseRequestPersistencePort persistence = mock(PurchaseRequestPersistencePort.class);
        PurchaseRequestEventPersistencePort events = mock(PurchaseRequestEventPersistencePort.class);
        IdempotencyPersistencePort idempotency = mock(IdempotencyPersistencePort.class);
        CatalogItemSnapshotLookupPort catalog = mock(CatalogItemSnapshotLookupPort.class);
        CustomerAccountQuery accounts = mock(CustomerAccountQuery.class);
        ChangeEventPersistencePort changeFeed = mock(ChangeEventPersistencePort.class);
        var context = SalesTestFixtures.buyerContext();
        PurchaseRequestView draft = new PurchaseRequestView(REQUEST_ID, "PR-LEAD", "client",
                context.membershipId().toString(), "DRAFT", "NORMAL", TOO_EARLY, "delivery", "IMMEDIATE",
                "comment", null, List.<PurchaseRequestLineView>of(), 0);
        when(accounts.findBuyerReference(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference(
                        "client", "ACTIVE")));
        when(persistence.find(anyString(), anyString(), any(), anyString())).thenReturn(Optional.of(draft));
        when(idempotency.find(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        PurchaseRequestService service = new PurchaseRequestService(persistence, events, idempotency, catalog,
                accounts, changeFeed, null, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.transition(context, REQUEST_ID, "submit", null, 0, "submit-1"))
                .isInstanceOf(MinimumDeliveryLeadBusinessDaysViolationException.class);

        verify(idempotency).lock(context.tenantId().toString(), context.workspaceId().toString(),
                context.membershipId().toString(), "purchase-request-submission", "submit-1");
        verifyNoInteractions(events, catalog, changeFeed);
        org.mockito.Mockito.verify(persistence, org.mockito.Mockito.never())
                .transition(anyString(), anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), anyLong());
    }
}
