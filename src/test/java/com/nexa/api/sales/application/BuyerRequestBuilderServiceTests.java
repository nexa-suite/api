package com.nexa.api.sales.application;

import com.nexa.api.sales.SalesTestFixtures;
import com.nexa.api.sales.application.buyerrequest.model.BuyerRequestView;
import com.nexa.api.sales.application.buyerrequest.model.CreateBuyerRequestCommand;
import com.nexa.api.sales.application.buyerrequest.port.BuyerRequestPersistencePort;
import com.nexa.api.sales.application.buyerrequest.service.BuyerRequestBuilderService;
import com.nexa.api.sales.application.purchaserequest.model.PurchaseRequestLineView;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequest;
import com.nexa.api.sales.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.sales.domain.model.purchaserequest.PaymentOption;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BuyerRequestBuilderServiceTests {
    @Test
    void buyerBuilderCreatesAllRequiredSnapshotsThroughTheUseCase() {
        CapturingPersistence persistence = new CapturingPersistence();
        BuyerRequestBuilderService service = new BuyerRequestBuilderService(SalesTestFixtures.assembler(), persistence);
        CreateBuyerRequestCommand command = new CreateBuyerRequestCommand(null, SalesTestFixtures.ADDRESS.toString(), null,
                java.time.LocalDate.now().plusDays(2), "Leave at cold room", SalesTestFixtures.WAREHOUSE.toString(),
                "LOCAL", PaymentOption.CASH_ON_DELIVERY, "No substitutions",
                List.of(new CreateBuyerRequestCommand.Line("ITEM-001", BigDecimal.valueOf(2), "bag", "Frozen")));

        BuyerRequestSnapshot preview = service.preview(SalesTestFixtures.buyerContext(), command);
        BuyerRequestView created = service.create(SalesTestFixtures.buyerContext(), command);

        assertThat(preview.delivery().address().id()).isEqualTo(SalesTestFixtures.ADDRESS.toString());
        assertThat(preview.delivery().warehouse().code()).isEqualTo("WH-LIM-01");
        assertThat(preview.commercial().clientAccountId()).isEqualTo(SalesTestFixtures.ACCOUNT);
        assertThat(preview.payment().option()).isEqualTo(PaymentOption.CASH_ON_DELIVERY);
        assertThat(created.lines()).hasSize(1);
        assertThat(persistence.saveCount).isEqualTo(1);
    }

    private static final class CapturingPersistence implements BuyerRequestPersistencePort {
        private int saveCount;

        @Override
        public BuyerRequestView save(BuyerRequest request, String tenantId, String workspaceId, String code, long now) {
            saveCount++;
            List<PurchaseRequestLineView> lines = request.lines().stream().map(line -> new PurchaseRequestLineView(
                    line.id().value().toString(), line.catalogItem().catalogItemId(), line.catalogItem().itemName(),
                    line.catalogItem().presentation(), line.quantity().value(), line.unit(), line.catalogItem().price().amount(),
                    line.catalogItem().price().currency(), line.notes(), 0)).toList();
            return new BuyerRequestView(request.id().value(), code, tenantId, workspaceId, request.clientAccountId(),
                    request.buyerMembershipId().value().toString(), request.status().name(), request.snapshot(), lines, request.version());
        }

        @Override
        public Optional<BuyerRequestView> find(String tenantId, String workspaceId, String requestId) { return Optional.empty(); }
    }
}
