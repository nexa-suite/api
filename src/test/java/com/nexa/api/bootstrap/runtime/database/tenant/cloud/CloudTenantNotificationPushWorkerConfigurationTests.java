package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBinding;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseBindingRegistry;
import com.nexa.api.bootstrap.runtime.database.tenant.local.TenantBusinessDatabaseMigrationRequirements;
import com.nexa.api.businessdocuments.tenantdatabase.TenantBusinessDocumentWorkerScopeQuery;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudTenantNotificationPushWorkerConfigurationTests {
    private static final String PREFIX = "nexa.tenant-business.cloud-router.environment.";
    private static final UUID TENANT = UUID.fromString("032b470c-157f-347c-b1df-d00e62788fb2");
    private static final UUID DATABASE = UUID.fromString("714cbaee-6090-405c-a87c-497a2ebfa640");
    private static final String REFERENCE = "cloud-tenant-db:0123456789abcdef0123456789abcdef";

    @ParameterizedTest
    @ValueSource(strings = {"spring.datasource.password", "NEXA_DATABASE_PASSWORD", "NEXA_DATABASE_RUNTIME_PASSWORD",
            "NEXA_DATABASE_MIGRATOR_PASSWORD", "NEXA_BUSINESS_DOCUMENTS_SCOPE_READER_PASSWORD",
            PREFIX + "runtime-password", PREFIX + "policy-writer-password"})
    void rejectsReusedPasswords(String key) {
        MockEnvironment environment = environment().withProperty(key, "worker-test-secret");
        assertThatThrownBy(() -> configuration().notificationPushWorkerRouter(mock(TenantBusinessDatabaseBindingRegistry.class),
                mock(TenantBusinessDocumentWorkerScopeQuery.class), environment, 1, 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloud Tenant database credentials are unavailable or unsafe");
    }

    @Test
    void rejectsScopeReaderPasswordReusedByWorkerBeforeOpeningCentralConnection() {
        assertThatThrownBy(() -> configuration().notificationPushWorkerScopes("jdbc:postgresql://localhost/unused",
                "worker-test-secret", environment())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scope reader credentials");
    }

    @Test
    void rejectsDifferentReadyBindingBeforeOpeningTenantConnection() throws Exception {
        var bindings = mock(TenantBusinessDatabaseBindingRegistry.class);
        var scopes = mock(TenantBusinessDocumentWorkerScopeQuery.class);
        UUID workspace = UUID.randomUUID();
        String digest = TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest();
        when(scopes.isReadyWorkspace(TENANT, workspace)).thenReturn(true);
        when(bindings.findReadyBinding(new TenantId(TENANT))).thenReturn(Optional.of(
                new TenantBusinessDatabaseBinding(new TenantId(TENANT), UUID.randomUUID(), REFERENCE, digest)));
        try (var router = (AutoCloseable) configuration().notificationPushWorkerRouter(bindings, scopes, environment(), 1, 1)) {
            var routed = (com.nexa.api.businesstraceability.tenantdatabase.TenantBusinessTraceabilityWorkerRouter) router;
            assertThatThrownBy(() -> routed.inTransaction(TENANT, workspace, (jdbc, transaction) -> null))
                    .hasMessageContaining("credentials are unavailable or unsafe");
        }
    }

    private static CloudTenantNotificationPushWorkerConfiguration configuration() {
        return new CloudTenantNotificationPushWorkerConfiguration();
    }

    private static MockEnvironment environment() {
        return CloudTenantDatabaseEnvironmentCredentialsTests.environment()
                .withProperty("nexa.tenant-business.cloud-router.credentials-source", "environment")
                .withProperty(PREFIX + "notification-worker-password", "worker-test-secret");
    }
}
