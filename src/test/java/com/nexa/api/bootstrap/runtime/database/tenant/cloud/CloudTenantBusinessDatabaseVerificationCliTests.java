package com.nexa.api.bootstrap.runtime.database.tenant.cloud;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class CloudTenantBusinessDatabaseVerificationCliTests {
    private Map<String,String> valid() {
        Map<String,String> values = new HashMap<>();
        for (String key : new String[] {"tenant-id","workspace-id","database-identity"}) values.put(key, UUID.randomUUID().toString());
        values.put("credential-secret-reference", "cloud-tenant-db:" + "a".repeat(32));
        values.put("jdbc-url", "jdbc:postgresql://ep-tenant.c-7.us-east-2.aws.neon.tech:5432/nexa_tenant_business?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory&channelBinding=require");
        values.put("central-jdbc-url", "jdbc:postgresql://ep-central.c-7.us-east-2.aws.neon.tech:5432/central");
        for (String key : new String[] {"runtime-password","policy-writer-password","migrator-password","central-runtime-password","central-migrator-password"}) values.put(key, "test-only-" + key);
        return values;
    }

    @Test
    void commandRejectsUnknownOperationWithoutEchoingInputs() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                CloudTenantBusinessDatabaseVerificationCli.class.getName(), "--migrate=do-not-echo-this")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isEqualTo(1);
        assertThat(output).contains("Cloud Tenant verification failed")
                .doesNotContain("do-not-echo-this", "Exception", "jdbc:");
    }

    @Test
    void flywayLoggerInitializesWithoutLoggingConnectionMaterial() throws Exception {
        Process process = new ProcessBuilder(javaExecutable(), "-cp", System.getProperty("java.class.path"),
                LoggerInitialization.class.getName()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).isZero();
        assertThat(output).isEqualTo("logger-ready" + System.lineSeparator());
    }

    public static final class LoggerInitialization {
        public static void main(String[] args) {
            org.flywaydb.core.Flyway.configure()
                    .dataSource("jdbc:postgresql://invalid.invalid/private-database", "private-user", "private-password")
                    .loggers(CloudTenantBusinessDatabaseVerificationCli.SilentLogCreator.class.getName()).load();
            System.out.println("logger-ready");
        }
    }

    private String javaExecutable() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    void acceptsOnlyExplicitDistinctCloudMaterialWithoutConnecting() {
        assertThatCode(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(valid())).doesNotThrowAnyException();
    }

    @Test
    void rejectsWeakTlsPoolersExtraOptionsAndLocalReferencesWithRedactedErrors() {
        for (String replacement : new String[] {"jdbc:postgresql://localhost:5432/nexa_tenant_business",
                valid().get("jdbc-url").replace("verify-full", "require"),
                valid().get("jdbc-url").replace("ep-tenant.", "ep-tenant-pooler."),
                valid().get("jdbc-url") + "&password=do-not-echo-this"}) {
            Map<String,String> values = valid(); values.put("jdbc-url", replacement);
            assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(values))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Cloud Tenant verification inputs unavailable")
                    .hasNoCause();
        }
        Map<String,String> values = valid(); values.put("credential-secret-reference", "local-tenant-db:" + UUID.randomUUID());
        assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(values))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsReusedPasswordsSameComputeWrongScopeAndUnknownKeys() {
        Map<String,String> values = valid(); values.put("migrator-password", values.get("runtime-password"));
        assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(values)).isInstanceOf(IllegalStateException.class);
        Map<String,String> sameCompute = valid(); sameCompute.put("central-jdbc-url", sameCompute.get("jdbc-url"));
        assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(sameCompute)).isInstanceOf(IllegalStateException.class);
        Map<String,String> wrongScope = valid(); wrongScope.put("tenant-id", "not-a-uuid");
        assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(wrongScope)).isInstanceOf(IllegalStateException.class);
        Map<String,String> unknown = valid(); unknown.put("mode", "migrate");
        assertThatThrownBy(() -> CloudTenantBusinessDatabaseVerificationCli.validateConfiguration(unknown)).isInstanceOf(IllegalStateException.class);
    }
}
