package com.nexa.api.salescommitment.infrastructure.salesorder;

import com.nexa.api.customerbuyerrelationships.contract.Address;
import com.nexa.api.salescommitment.application.salesorder.model.ManualSalesOrderView;
import com.nexa.api.salescommitment.domain.model.commercial.CommercialSnapshot;
import com.nexa.api.salescommitment.domain.model.commercial.PaymentTerms;
import com.nexa.api.salescommitment.domain.model.credit.CreditProfile;
import com.nexa.api.salescommitment.domain.model.credit.CreditStatus;
import com.nexa.api.salescommitment.domain.model.delivery.DeliveryAddressSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.DeliverySnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.RouteSnapshot;
import com.nexa.api.salescommitment.domain.model.delivery.WarehouseSnapshot;
import com.nexa.api.salescommitment.domain.model.payment.PaymentSnapshot;
import com.nexa.api.salescommitment.domain.model.purchaserequest.PaymentOption;
import com.nexa.api.salescommitment.domain.publicapi.SalesInvariantViolation;
import com.nexa.api.salescommitment.domain.model.salesorder.ManualSalesOrderSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManualSalesOrderSnapshotReadTests {
    private final JsonMapper mapper = JsonMapper.shared();

    @Test
    void readsHistoricalSnapshotAsViewWithoutReapplyingCreationDateValidation() throws Exception {
        LocalDate requestedDate = LocalDate.now().plusDays(1);
        var snapshot = new ManualSalesOrderSnapshot(
                new DeliverySnapshot(requestedDate, "Dock 2",
                        new DeliveryAddressSnapshot("address-1", "Main entrance",
                                Address.peru("STREET", "Av. Lima 123", "Dock 2", "15", "1501", "150101"), true),
                        new WarehouseSnapshot("warehouse-1", "WH-01", "Main warehouse", "Av. Lima 123",
                                "ACTIVE_OPERATIONAL_FALLBACK", "OPERATIONAL", 1, true,
                                Instant.parse("2026-10-10T12:00:00Z"), null, null),
                        new RouteSnapshot("LOCAL", "route-1", "Origin", "Destination", 100, 60,
                                "https://maps.example/preview", null, null, null, null,
                                Instant.parse("2026-10-10T12:00:00Z"), "DRIVING", "encoded-path")),
                new CommercialSnapshot("account-1", "Business", "Commercial", "tax-1",
                        new CreditProfile(new BigDecimal("1000.00"), new BigDecimal("100.00"), CreditStatus.AVAILABLE),
                        new PaymentTerms("NET30", "Net 30", 30, true), true),
                new PaymentSnapshot(PaymentOption.CREDIT_LINE, "NET30", new BigDecimal("90.00"), "PEN", true),
                Instant.parse("2026-10-10T12:00:00Z"), "Order note");
        String json = mapper.writeValueAsString(snapshot)
                .replace(requestedDate.toString(), "2020-01-02");

        ManualSalesOrderView.SnapshotView view = ManualSalesOrderPersistenceAdapter.readSnapshotView(json, mapper);

        assertThat(view.delivery().requestedDate()).isEqualTo(LocalDate.of(2020, 1, 2));
        assertThat(view.delivery().address().address().line()).isEqualTo("Av. Lima 123");
        assertThat(view.delivery().warehouse().code()).isEqualTo("WH-01");
        assertThat(view.delivery().route().mode()).isEqualTo("DRIVING");
        assertThat(view.commercial().credit().status()).isEqualTo("AVAILABLE");
        assertThat(view.payment().option()).isEqualTo("CREDIT_LINE");
        assertThat(view.capturedAt()).isEqualTo(Instant.parse("2026-10-10T12:00:00Z"));

        assertThatThrownBy(() -> new DeliverySnapshot(LocalDate.of(2020, 1, 2), "Dock 2",
                snapshot.delivery().address(), snapshot.delivery().warehouse(), snapshot.delivery().route()))
                .isInstanceOf(SalesInvariantViolation.class)
                .hasMessage("Requested delivery date cannot be in the past");
    }
}
