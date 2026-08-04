package com.nexa.api.logistics.infrastructure;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.DispatchCommandPersistencePort;
import com.nexa.api.logistics.application.port.DispatchQueryPersistencePort;
import com.nexa.api.logistics.application.port.DispatchRouteStartPort;
import com.nexa.api.logistics.application.port.OperationalHandoffPort;
import com.nexa.api.warehouse.application.port.WarehouseLogisticsFulfillmentPort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
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
                                                          ClientAccountPersistencePort accounts) {
        return new LogisticsOperationsService(queries, commands, accounts,
                new com.nexa.api.logistics.application.service.StartDispatchRouteService(routeStart, warehouse), handoff);
    }
}
