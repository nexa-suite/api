package com.nexa.api.sales.infrastructure;

import com.nexa.api.sales.application.port.in.SalesUseCase;
import com.nexa.api.sales.application.port.out.SalesPort;
import com.nexa.api.sales.application.service.SalesService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SalesRuntimeConfiguration {
	@Bean SalesUseCase salesUseCase(SalesPort port) { return new SalesService(port); }
}
