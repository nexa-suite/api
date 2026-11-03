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
    void warehouseCapabilitiesRequireAddressAndProductionDateMigrations() {
        var versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("24");
        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V24");
        versions.add("24");
        versions.remove("25");
        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V25");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutParentScopedRlsMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("26");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V26");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutBuyerCatalogFavoritesMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("29");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V29");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutAppendOnlyChangeFeedMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("30");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V30");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutDocumentGenerationRlsMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("31");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V31");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutBuyerAccountTypeMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("33");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V33");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutBuyerMembershipLifecycleMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("37");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V37");
    }

    @Test
    void tenantDatabaseCannotBecomeReadyWithoutBuyerAddressGeographyUpdateMigration() {
        Set<String> versions = new HashSet<>(TenantBusinessDatabaseMigrationRequirements.load().requiredVersions());
        versions.remove("43");

        assertThatThrownBy(() -> LocalTenantBusinessDatabaseProvisioningCli.verifyRequiredMigrations(versions))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V43");
    }

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
                        "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25",
                        "26", "27", "28", "29", "30", "31", "33", "34", "35", "36", "37", "38", "39",
                        "40", "41", "42", "43")))
                .doesNotThrowAnyException();
    }

    @Test
    void activeCapabilityManifestRequiresItsSqlAssetsAndPreservesTheVersionSixGap() {
        TenantBusinessDatabaseMigrationRequirements requirements = TenantBusinessDatabaseMigrationRequirements.load();

        assertThat(requirements.activeCapabilities()).contains(
                "buyer-wallet", "buyer-wallet-refunds", "wallet-purchase-request-tender",
                "fulfillment-dispatch-actor", "buyer-wallet-provider-recharge",
                "tenant-business-traceability-worker", "tenant-payments-callback-worker",
                "tenant-business-notification-projection", "tenant-parent-scoped-rls",
                "tenant-inbox-event-rls", "tenant-buyer-catalog-favorites", "tenant-change-event-append-only",
                "tenant-document-generation-workspace-rls", "tenant-buyer-sales-messaging",
                "tenant-buyer-delivery-acceptance", "tenant-buyer-saved-payment-methods",
                "tenant-client-account-buyer-memberships", "tenant-buyer-personal-address-book",
                "tenant-catalog-sku-variant-family");
        assertThat(requirements.requiredVersions()).containsExactly(
                "1", "2", "3", "4", "5", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16",
                "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31",
                "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43");
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
        assertThat(requiredAssets).contains("db/tenant-migration/V24__structure_warehouse_addresses.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V25__record_inventory_production_date.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V26__force_parent_scoped_business_rls.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V28__force_direct_scope_rls_for_inbox_event.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V29__create_buyer_catalog_offer_favorites.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V30__revoke_runtime_change_event_mutation.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V31__force_document_generation_request_workspace_rls.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V33__model_buyer_account_type.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V34__create_buyer_sales_account_messages.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V35__bind_buyer_acceptance_to_delivery_pod.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V36__add_buyer_saved_payment_methods.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V37__manage_client_account_buyer_memberships.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V38__create_buyer_personal_addresses.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V39__preserve_buyer_address_type.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V40__enforce_sku_variant_family_scope.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V41__bind_buyer_handoff_to_active_attempt.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V42__grant_payment_callback_buyer_link_read.sql");
        assertThat(requiredAssets).contains("db/tenant-migration/V43__grant_buyer_address_geography_updates.sql");
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
