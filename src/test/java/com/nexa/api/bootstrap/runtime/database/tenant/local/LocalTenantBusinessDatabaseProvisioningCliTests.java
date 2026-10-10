package com.nexa.api.bootstrap.runtime.database.tenant.local;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTenantBusinessDatabaseProvisioningCliTests {

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutEveryActiveCapabilityMigration() {
        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli
                .verifyRequiredMigrations(Set.of("1", "2", "3", "4", "5", "7")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V8");
    }

    @Test
    void tenantDatabaseCanBecomeReadyAfterAllActiveCapabilityMigrations() {
        assertThatCode(() -> LocalTenantBusinessDatabaseProvisioningCli
                .verifyRequiredMigrations(Set.of("1", "2", "3", "4", "5", "7", "8", "9")))
                .doesNotThrowAnyException();
    }

    @Test
    void activeCapabilityManifestRequiresItsSqlAssetsAndDoesNotInventVersionSix() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();

        assertThat(requirements.activeCapabilities()).contains(
                "buyer-wallet", "wallet-purchase-request-tender", "fulfillment-dispatch-actor",
                "buyer-wallet-provider-recharge");
        assertThat(requirements.requiredVersions()).containsExactly("1", "2", "3", "4", "5", "7", "8", "9");
        assertThatCode(requirements::verifyRequiredSqlAssets).doesNotThrowAnyException();
        assertThat(requirements.schemaManifestDigest()).matches("[0-9a-f]{64}");
        assertThat(TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest())
                .isEqualTo(requirements.schemaManifestDigest());
    }

    @Test
    void readinessFailsClosedWhenARequiredMigrationSqlAssetIsMissing() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        ClassLoader withoutV8 = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public URL getResource(String name) {
                return name.endsWith("V8__record_dispatch_actor_on_fulfillment_handoff.sql")
                        ? null : super.getResource(name);
            }
        };

        assertThatThrownBy(() -> requirements.verifyRequiredSqlAssets(withoutV8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V8")
                .hasMessageContaining("SQL asset is missing");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutWalletRechargeMigration() {
        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli
                .verifyRequiredMigrations(Set.of("1", "2", "3", "4", "5", "7", "8")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V9");
    }
}
