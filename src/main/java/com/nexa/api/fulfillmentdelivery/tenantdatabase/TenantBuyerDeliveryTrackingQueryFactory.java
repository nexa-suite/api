package com.nexa.api.fulfillmentdelivery.tenantdatabase;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.fulfillmentdelivery.application.publicapi.BuyerDeliveryTrackingQueries;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.NamedInterface;

/** Binds Buyer delivery status reads to the same verified Tenant session as account and order queries. */
@NamedInterface(value = "fulfillment-tenant-composition", propagate = false)
@FunctionalInterface
public interface TenantBuyerDeliveryTrackingQueryFactory {
    BuyerDeliveryTrackingQueries bindBuyerTracking(JdbcTemplate tenantJdbc, CustomerAccountQuery accounts,
                                                    SalesOrderFulfillmentQuery orders);
}
