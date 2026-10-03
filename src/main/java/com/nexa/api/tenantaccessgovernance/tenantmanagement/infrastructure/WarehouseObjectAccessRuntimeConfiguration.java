package com.nexa.api.tenantaccessgovernance.tenantmanagement.infrastructure;

import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.SecurityAuditPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.AuthorizationVersionPort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.port.out.WarehouseAccessGrantPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.WarehouseObjectAccess;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.service.WarehouseObjectAccessService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class WarehouseObjectAccessRuntimeConfiguration {
    @Bean
    WarehouseObjectAccess warehouseObjectAccess(WarehouseAccessGrantPersistencePort grants,
                                                 AuthorizationVersionPort authorizationVersions,
                                                 ChangeEventPersistencePort changes, SecurityAuditPort audit,
                                                 PlatformTransactionManager transactionManager) {
        return TenantTransactionalProxy.required(new WarehouseObjectAccessService(grants, authorizationVersions,
                changes, audit), WarehouseObjectAccess.class, transactionManager);
    }
}
