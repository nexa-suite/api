package com.nexa.api.logistics.infrastructure;

import com.nexa.api.logistics.application.LogisticsOperationsService;
import com.nexa.api.logistics.application.port.LogisticsPersistencePort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class LogisticsRuntimeConfiguration {
    @Bean
    LogisticsOperationsService logisticsOperationsService(LogisticsPersistencePort persistence, ClientAccountPersistencePort accounts) {
        return new LogisticsOperationsService(persistence, accounts);
    }
}
