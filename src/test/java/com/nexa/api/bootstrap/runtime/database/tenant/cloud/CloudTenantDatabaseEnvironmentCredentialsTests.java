package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePolicySnapshotWriterUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudTenantDatabaseEnvironmentCredentialsTests {
    private static final String PREFIX = "nexa.tenant-business.cloud-router.environment.";
    private static final String REFERENCE = "cloud-tenant-db:0123456789abcdef0123456789abcdef";
    private static final String TENANT = "032b470c-157f-347c-b1df-d00e62788fb2";
    private static final String DATABASE = "714cbaee-6090-405c-a87c-497a2ebfa640";
    private static final String JDBC = "jdbc:postgresql://ep-example-123.c-7.us-east-2.aws.neon.tech:5432/nexa_tenant_business"
            + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require";

    @Test
    void bindsOnlyTheExplicitOpaqueReferenceAndWriterIdentityWithSeparateRoles() {
        var credentials = new CloudTenantDatabaseEnvironmentCredentials(environment());
        assertThat(credentials.requireCredentials(REFERENCE).username()).isEqualTo("nexa_runtime");
        assertThat(credentials.requireWriterCredentials(new TenantId(TENANT), UUID.fromString(DATABASE)).username())
                .isEqualTo("nexa_policy_snapshot_writer");
        assertThat(credentials.toString()).doesNotContain(REFERENCE, JDBC, "runtime-test-secret", "writer-test-secret");
        assertThat(credentials.requireCredentials(REFERENCE).toString()).doesNotContain(JDBC, "runtime-test-secret");
        assertThatThrownBy(() -> credentials.requireCredentials(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> credentials.requireCredentials("cloud-tenant-db:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> credentials.requireWriterCredentials(new TenantId(UUID.randomUUID()), UUID.fromString(DATABASE)))
                .isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
        assertThatThrownBy(() -> credentials.requireWriterCredentials(new TenantId(TENANT), UUID.randomUUID()))
                .isInstanceOf(TenantBusinessDatabasePolicySnapshotWriterUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = { "credential-secret-reference", "tenant-id", "database-identity", "jdbc-url",
            "runtime-username", "runtime-password", "policy-writer-username", "policy-writer-password" })
    void incompleteMaterialFailsClosedWithoutDisclosingValues(String field) {
        var environment = environment().withProperty(PREFIX + field, "");
        assertThatThrownBy(() -> new CloudTenantDatabaseEnvironmentCredentials(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloud Tenant database credentials are unavailable or unsafe");
    }

    @Test
    void refusesOwnerRolesMalformedIdentitySharedSecretsAndWeakerTls() {
        for (String[] invalid : new String[][] {
                { "runtime-username", "neondb_owner" }, { "policy-writer-username", "nexa_runtime" },
                { "tenant-id", "invalid-tenant" }, { "database-identity", "invalid-database" },
                { "policy-writer-password", "runtime-test-secret" },
                { "credential-secret-reference", "local-tenant-db:development" },
                { "jdbc-url", JDBC.replace("verify-full", "require") },
                { "jdbc-url", JDBC.replace("ep-example-123.", "ep-example-123-pooler.") } }) {
            assertThatThrownBy(() -> new CloudTenantDatabaseEnvironmentCredentials(
                    environment().withProperty(PREFIX + invalid[0], invalid[1])))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Cloud Tenant database credentials are unavailable or unsafe");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "spring.datasource.password", "NEXA_DATABASE_RUNTIME_PASSWORD",
            "NEXA_DATABASE_MIGRATOR_PASSWORD", "NEXA_DATABASE_PASSWORD" })
    void refusesReuseOfCentralConnectionMaterial(String centralProperty) {
        assertThatThrownBy(() -> new CloudTenantDatabaseEnvironmentCredentials(
                environment().withProperty(centralProperty, "runtime-test-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cloud Tenant database credentials are unavailable or unsafe");
        assertThatThrownBy(() -> new CloudTenantDatabaseEnvironmentCredentials(
                environment().withProperty(centralProperty, "writer-test-secret")))
                .isInstanceOf(IllegalStateException.class);
    }

    static MockEnvironment environment() {
        return new MockEnvironment().withProperty(PREFIX + "credential-secret-reference", REFERENCE)
                .withProperty(PREFIX + "tenant-id", TENANT).withProperty(PREFIX + "database-identity", DATABASE)
                .withProperty(PREFIX + "jdbc-url", JDBC).withProperty(PREFIX + "runtime-username", "nexa_runtime")
                .withProperty(PREFIX + "runtime-password", "runtime-test-secret")
                .withProperty(PREFIX + "policy-writer-username", "nexa_policy_snapshot_writer")
                .withProperty(PREFIX + "policy-writer-password", "writer-test-secret");
    }
}
