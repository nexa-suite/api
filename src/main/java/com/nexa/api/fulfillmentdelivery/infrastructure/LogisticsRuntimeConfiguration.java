package com.nexa.api.fulfillmentdelivery.infrastructure;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchCommandPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchQueryPersistencePort;
import com.nexa.api.fulfillmentdelivery.application.port.DispatchRouteStartPort;
import com.nexa.api.fulfillmentdelivery.application.port.OperationalHandoffPort;
import com.nexa.api.inventoryavailability.application.port.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class LogisticsRuntimeConfiguration {
    @Bean
    LogisticsOperationsService logisticsOperationsService(DispatchQueryPersistencePort queries,
                                                          DispatchCommandPersistencePort commands,
                                                          DispatchRouteStartPort routeStart,
                                                          OperationalHandoffPort handoff,
                                                          WarehouseLogisticsFulfillmentPort warehouse,
                                                          CustomerAccountQuery accounts) {
        return new LogisticsOperationsService(queries, commands, accounts,
                new com.nexa.api.fulfillmentdelivery.application.service.StartDispatchRouteService(routeStart, warehouse), handoff);
    }
}
