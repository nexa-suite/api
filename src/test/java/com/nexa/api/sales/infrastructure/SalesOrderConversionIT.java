package com.nexa.api.sales.infrastructure;

import com.nexa.api.support.NexaWorkflowIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class SalesOrderConversionIT extends NexaWorkflowIntegrationSupport {
    @Test void convertsApprovedPurchaseRequestThroughRealHttpAndPersistsBothAggregates() throws Exception {
        var request = createApprovedPurchaseRequest();
        var order = convert(request, "conversion-" + uuid());
        assertThat(order.id()).isNotBlank();
        assertThat(jdbc.queryForObject("select status from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo("CONVERTED_TO_ORDER");
        assertThat(jdbc.queryForObject("select count(*) from sales.sales_order where source_purchase_request_id=?", Integer.class, java.util.UUID.fromString(request.id()))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select created_by_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(membershipId(SALES_EMAIL));
        assertThat(jdbc.queryForObject("select buyer_membership_id::text from sales.sales_order where source_purchase_request_id=?", String.class, java.util.UUID.fromString(request.id()))).isEqualTo(jdbc.queryForObject("select buyer_membership_id::text from sales.purchase_request where id=?", String.class, java.util.UUID.fromString(request.id())));
    }
}
