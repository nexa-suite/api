package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.businesstraceability.application.port.in.AuditViewerUseCase;
import com.nexa.api.businesstraceability.tenantdatabase.TenantAuditViewerQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeFeedQueryFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Enables authenticated BC-11 reads only when the opt-in Tenant capability is active. */
@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(name = "nexa.tenant-business.business-traceability.enabled", havingValue = "true")
public class TenantBoundBusinessTraceabilityConfiguration {
    @Bean
    @Primary
    AuditViewerUseCase tenantAuditViewerUseCase(TenantBusinessDatabaseRouter router,
            TenantAuditViewerQueryFactory queries) {
        return new TenantBoundBusinessTraceabilityUseCase(router, queries);
    }

    @Bean
    @Primary
    ChangeFeedReadUseCase tenantChangeFeedReadUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory accounts, TenantChangeFeedQueryFactory feed) {
        return new TenantBoundChangeFeedReadUseCase(router, accounts, feed);
    }
}
