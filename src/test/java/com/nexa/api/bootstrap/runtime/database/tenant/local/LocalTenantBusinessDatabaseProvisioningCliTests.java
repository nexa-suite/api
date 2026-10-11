package com.nexa.api.bootstrap.runtime.database.tenant.local;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.util.HashSet;
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
                .verifyRequiredMigrations(Set.of("1", "2", "3", "4", "5", "7", "8", "9", "10", "11",
                        "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23")))
                .doesNotThrowAnyException();
    }

    @Test
    void activeCapabilityManifestRequiresItsSqlAssetsAndPreservesTheVersionSixGap() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();

        assertThat(requirements.activeCapabilities()).contains(
                "buyer-wallet", "buyer-wallet-refunds", "wallet-purchase-request-tender",
                "fulfillment-dispatch-actor", "buyer-wallet-provider-recharge",
                "tenant-business-traceability-worker", "tenant-payments-callback-worker",
                "tenant-business-notification-projection");
        assertThat(requirements.requiredVersions()).containsExactly(
                "1", "2", "3", "4", "5", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                "17", "18", "19", "20", "21", "22", "23");
        Set<String> requiredAssets = new HashSet<>();
        ClassLoader recordingClassLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public URL getResource(String name) {
                if (name.startsWith("db/tenant-migration/V")) requiredAssets.add(name);
                return super.getResource(name);
            }
        };
        assertThatCode(() -> requirements.verifyRequiredSqlAssets(recordingClassLoader)).doesNotThrowAnyException();
        assertThat(requiredAssets).contains("db/tenant-migration/V16__record_buyer_wallet_refund_evidence.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V18__grant_traceability_worker_expiry_release.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V19__grant_traceability_worker_material_change_expiry.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V20__grant_traceability_worker_notification_push_delivery.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V21__grant_payment_callback_worker_idempotent_event_keys.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V22__remove_mutable_derived_columns.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V23__bound_tenant_push_delivery_retry_state.sql");
        assertThat(requirements.schemaManifestDigest()).matches("[0-9a-f]{64}");
        assertThat(TenantBusinessDatabaseMigrationRequirements.load().schemaManifestDigest())
                .isEqualTo(requirements.schemaManifestDigest());
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutWalletRefundEvidenceMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("16");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V16");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutTraceabilityExpiryPolicyReadMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("18");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V18");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutTraceabilityMaterialChangeExpiryMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("19");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V19");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutTraceabilityNotificationPushDeliveryMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("20");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutPaymentCallbackIdempotencyMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("21");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V21");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutDerivedColumnRemovalMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("22");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V22");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutBoundedPushDeliveryRetryMigration() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();
        Set<String> appliedVersions = new HashSet<>(requirements.requiredVersions());
        appliedVersions.remove("23");

        assertThatThrownBy(() -> requirements.verifyRequiredMigrations(appliedVersions))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V23");
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
