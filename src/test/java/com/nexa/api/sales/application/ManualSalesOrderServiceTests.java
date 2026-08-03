package com.nexa.api.sales.application;

import com.nexa.api.sales.SalesTestFixtures;
import com.nexa.api.sales.application.salesorder.model.CreateManualSalesOrderCommand;
import com.nexa.api.sales.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.sales.application.salesorder.model.SalesOrderLineView;
import com.nexa.api.sales.application.salesorder.port.ManualSalesOrderPersistencePort;
import com.nexa.api.sales.application.salesorder.service.ManualSalesOrderService;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.sales.domain.model.purchaserequest.PurchaseRequestPriority;
import com.nexa.api.sales.domain.model.salesorder.ManualSalesOrder;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderId;
import com.nexa.api.sales.domain.model.salesorder.SalesOrderNumber;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ManualSalesOrderServiceTests {
    @Test
    void manualOrderUsesIdempotencyBeforeAllocatingASecondIdentity() {
        CapturingPersistence persistence = new CapturingPersistence();
        ManualSalesOrderService service = new ManualSalesOrderService(SalesTestFixtures.assembler(), persistence);
        CreateManualSalesOrderCommand command = new CreateManualSalesOrderCommand(SalesTestFixtures.ACCOUNT,
                SalesTestFixtures.ADDRESS.toString(), null, java.time.LocalDate.now().plusDays(1), "Dock 2",
                SalesTestFixtures.WAREHOUSE.toString(), "LOCAL", PaymentOption.CREDIT_LINE,
                PurchaseRequestPriority.HIGH, "PEN", "Call before loading",
                List.of(new CreateManualSalesOrderCommand.Line("ITEM-001", BigDecimal.valueOf(3), "bag", null)));

        ManualSalesOrderView first = service.create(SalesTestFixtures.salesContext(), command, "manual-key-1");
        ManualSalesOrderView replay = service.create(SalesTestFixtures.salesContext(), command, "manual-key-1");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(replay.snapshot().delivery().address().address().line()).isEqualTo("Av. Lima 123");
        assertThat(replay.snapshot().commercial().credit().available()).isEqualByComparingTo("900");
        assertThat(persistence.identityCalls).isEqualTo(1);
        assertThat(persistence.saveCalls).isEqualTo(1);
    }

    private static final class CapturingPersistence implements ManualSalesOrderPersistencePort {
        private ManualSalesOrderView saved;
        private String hash;
        private int identityCalls;
        private int saveCalls;

        @Override
        public Optional<ManualSalesOrderView> findByIdempotency(String tenant, String workspace, String actor,
                                                                String key, String requestHash) {
            return saved == null ? Optional.empty() : Optional.of(saved);
        }

        @Override
        public SalesOrderIdentity nextIdentity(String tenant, String workspace) {
            identityCalls++;
            return new SalesOrderIdentity(new SalesOrderId("SO-ID-1"), new SalesOrderNumber("SO-2026-000001"));
        }

        @Override
        public ManualSalesOrderView save(ManualSalesOrder order, String actor, String key, String requestHash, long now) {
            saveCalls++;
            hash = requestHash;
            List<SalesOrderLineView> lines = order.lines().stream().map(line -> new SalesOrderLineView(line.catalogItemId(),
                    line.itemNameSnapshot(), line.presentationSnapshot(), line.quantity(), line.unit(), line.unitPriceAmount(),
                    line.unitPriceCurrency(), line.lineSubtotal())).toList();
            saved = new ManualSalesOrderView(order.id().value(), order.number().value(), order.tenantId().toString(),
                    order.workspaceId().toString(), order.clientAccountId().value(), order.createdByMembershipId().toString(),
                    order.priority(), order.requestedDeliveryDate(), order.snapshot(), order.snapshot().payment().currency(),
                    order.total(), order.status().name(), order.createdAt(), Instant.ofEpochMilli(now), order.version(), lines);
            return saved;
        }
    }
}
