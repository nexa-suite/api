package com.nexa.api.salescommitment.presentation.salesorder.mapper;

import com.nexa.api.salescommitment.application.salesorder.model.FulfillmentCandidateView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SalesOrderHttpMapperTests {
    @Test
    void exposesTheAuthoritativeSalesOrderVersionToFulfillmentReaders() {
        FulfillmentCandidateView source = new FulfillmentCandidateView(
                "order-1", "SO-001", "client-1", "AWAITING_INVENTORY_RESERVATION", 7,
                List.of(new FulfillmentCandidateView.Line("catalog-1", "Gouda", new BigDecimal("2"), "UNIT")));

        var response = new SalesOrderHttpMapper().candidate(source);

        assertThat(response.version()).isEqualTo(7);
        assertThat(response.lines()).singleElement().satisfies(line -> {
            assertThat(line.catalogItemId()).isEqualTo("catalog-1");
            assertThat(line.quantity()).isEqualByComparingTo("2");
        });
    }
}
