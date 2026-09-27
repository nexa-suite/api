package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.fulfillmentdelivery.application.publicapi.FulfillmentInventoryQuery;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryCommercialSource;
import com.nexa.api.inventoryavailability.application.publicapi.InventoryFulfillmentSource;
import com.nexa.api.salescommitment.application.exception.CommercialBusinessException;
import com.nexa.api.salescommitment.application.publicapi.SalesOrderFulfillmentQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.Optional;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class InventorySourceConfiguration {
    @Bean
    InventoryCommercialSource inventoryCommercialSource(SalesOrderFulfillmentQuery sales) {
        return new InventoryCommercialSource() {
            @Override public Optional<Snapshot> findCandidate(UUID tenant, UUID workspace, UUID order) {
                return lookup(tenant, workspace, order, false);
            }
            @Override public Optional<Snapshot> claimCandidate(UUID tenant, UUID workspace, UUID order) {
                return lookup(tenant, workspace, order, true);
            }
            private Optional<Snapshot> lookup(UUID tenant, UUID workspace, UUID order, boolean claim) {
                try {
                    var value = claim ? sales.getForUpdate(tenant, workspace, order) : sales.get(tenant, workspace, order);
                    return Optional.of(new Snapshot(value.id(), value.number(), value.status(), value.version(),
                            value.clientAccountId(), value.commercialCommitmentId(), value.destinationSnapshot(),
                            value.lines().stream().map(line -> new Line(line.id(), line.skuId(), line.catalogItemId(), line.quantity(), line.unit())).toList()));
                } catch (CommercialBusinessException exception) {
                    if (!"SALES_ORDER_NOT_FOUND".equals(exception.code())) throw exception;
                    return Optional.empty();
                }
            }
        };
    }

    @Bean
    InventoryFulfillmentSource inventoryFulfillmentSource(FulfillmentInventoryQuery fulfillment) {
        return new InventoryFulfillmentSource() {
            @Override public boolean hasFulfillment(UUID tenant, UUID workspace, UUID order) {
                return fulfillment.hasFulfillment(tenant, workspace, order);
            }
            @Override public boolean hasActiveDispatch(UUID tenant, UUID workspace, UUID order) {
                return fulfillment.hasActiveDispatch(tenant, workspace, order);
            }
            @Override public Optional<UUID> physicalAllocationForDelivery(UUID tenant, UUID workspace, UUID delivery) {
                return fulfillment.physicalAllocationForDelivery(tenant, workspace, delivery);
            }
        };
    }
}
