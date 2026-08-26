package com.nexa.api.salescommitment.domain;

import com.nexa.api.salescommitment.SalesTestFixtures;
import com.nexa.api.salescommitment.domain.model.buyerrequest.BuyerRequest;
import com.nexa.api.salescommitment.domain.model.buyerrequest.BuyerRequestSnapshot;
import com.nexa.api.salescommitment.domain.model.commercial.CommercialSnapshot;
import com.nexa.api.salescommitment.domain.model.commercial.PaymentTerms;
import com.nexa.api.salescommitment.domain.model.credit.CreditProfile;
import com.nexa.api.salescommitment.domain.model.credit.CreditStatus;
import com.nexa.api.salescommitment.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.DeliverySnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.salescommitment.domain.model.payment.PaymentSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.BuyerMembershipId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestLine;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestLineId;
import com.nexa.api.salescommitment.domain.model.purchaserequest.RequestedQuantity;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesSnapshotTests {
    @Test
    void buyerRequestCopiesLinesAndKeepsDeliveryCommercialAndPaymentFactsImmutable() {
        DeliverySnapshot delivery = delivery();
        CommercialSnapshot commercial = new CommercialSnapshot(SalesTestFixtures.ACCOUNT, "Acme Foods", "Acme",
                "20123456789", new CreditProfile(BigDecimal.valueOf(1000), BigDecimal.valueOf(100), CreditStatus.AVAILABLE),
                new PaymentTerms("CREDIT_30", "Credit 30 days", 30, true), true);
        PaymentSnapshot payment = new PaymentSnapshot(PaymentOption.CASH_ON_DELIVERY, "CASH", BigDecimal.TEN, "PEN", true);
        BuyerRequestSnapshot snapshot = new BuyerRequestSnapshot(delivery, commercial, payment, Instant.now());
        List<PurchaseRequestLine> mutable = new ArrayList<>(List.of(line()));
        BuyerRequest request = BuyerRequest.draft(new PurchaseRequestId("PR-IMMUTABLE"), SalesTestFixtures.ACCOUNT,
                new BuyerMembershipId(SalesTestFixtures.MEMBERSHIP), mutable, snapshot);
        mutable.clear();

        assertThat(request.lines()).hasSize(1);
        assertThat(request.snapshot().address().address().line()).isEqualTo("Av. Lima 123");
        assertThat(request.snapshot().payment().amount()).isEqualByComparingTo(BigDecimal.TEN);
        assertThatThrownBy(() -> request.lines().add(line())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void manualSnapshotRejectsCreditWithoutAuthorizationAndPreservesOrderTotalRule() {
        assertThatThrownBy(() -> new PaymentSnapshot(PaymentOption.CREDIT_LINE, "CREDIT_30", BigDecimal.TEN, "PEN", false))
                .isInstanceOf(RuntimeException.class);
    }

    private static DeliverySnapshot delivery() {
        return new DeliverySnapshot(LocalDate.now().plusDays(1), "Keep frozen", new DeliveryAddressSnapshot(
                SalesTestFixtures.ADDRESS.toString(), "Main", SalesTestFixtures.address(), true),
                new WarehouseSnapshot(SalesTestFixtures.WAREHOUSE.toString(), "WH-LIM-01", "Lima Warehouse", "Av. Warehouse 1"),
                new RouteSnapshot("LOCAL_DETERMINISTIC", "LOCAL-1", "Warehouse", "Main", 1000, 300, "nexa://route"));
    }

    private static PurchaseRequestLine line() {
        return new PurchaseRequestLine(new PurchaseRequestLineId(java.util.UUID.randomUUID()), SalesTestFixtures.catalogItem(),
                new RequestedQuantity(BigDecimal.ONE), "bag", "Frozen");
    }
}
