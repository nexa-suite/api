package com.nexa.api.audit.infrastructure;

import com.nexa.api.audit.application.port.in.AuditViewerUseCase;
import com.nexa.api.audit.application.port.out.AuditViewerQueryPort;
import com.nexa.api.audit.application.service.AuditViewerService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class AuditRuntimeConfiguration {
	@Bean
	AuditViewerUseCase auditViewerUseCase(AuditViewerQueryPort query) { return new AuditViewerService(query); }
}
