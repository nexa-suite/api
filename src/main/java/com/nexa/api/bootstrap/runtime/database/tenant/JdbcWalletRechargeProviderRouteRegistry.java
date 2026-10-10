package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/** Minimal central BC-01 routing metadata for Stripe wallet callbacks; no balance or ledger facts. */
@Component
public final class JdbcWalletRechargeProviderRouteRegistry {
    private final JdbcTemplate centralJdbc;

    public JdbcWalletRechargeProviderRouteRegistry(JdbcTemplate centralJdbc) {
        this.centralJdbc = Objects.requireNonNull(centralJdbc, "Central route JdbcTemplate is required");
    }

    public void requireCallbackCredential(TenantBusinessDatabaseBinding businessBinding, WorkspaceId workspaceId) {
        requireScope(businessBinding.tenantId(), workspaceId);
        String secretReference = centralJdbc.query("select wallet_recharge_callback_credential_secret_reference "
                        + "from tenant_management.tenant_business_database_binding "
                        + "where tenant_id=? and database_identity=? and lifecycle_state='READY' "
                        + "and verified_schema_manifest_sha256=?",
                (rs, row) -> rs.getString(1), businessBinding.tenantId().value(),
                businessBinding.databaseIdentity(), businessBinding.verifiedSchemaManifestSha256())
                .stream().findFirst().orElse(null);
        if (secretReference == null || secretReference.isBlank()) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge callback credential is not provisioned");
        }
    }

    public Route registerPreparing(TenantBusinessDatabaseBinding binding, WorkspaceId workspaceId,
            UUID rechargeId, long amountMinor, String currency) {
        Objects.requireNonNull(rechargeId, "Recharge id is required");
        requireScope(binding.tenantId(), workspaceId);
        if (amountMinor < 1 || amountMinor > 99_999_999 || !"PEN".equals(currency)) {
            throw new IllegalArgumentException("Wallet recharge route amount is invalid");
        }
        centralJdbc.update("insert into tenant_management.wallet_recharge_provider_route "
                        + "(recharge_id,tenant_id,workspace_id,database_identity,provider_code,amount_minor,currency) "
                        + "select ?,b.tenant_id,?,b.database_identity,'STRIPE',?,'PEN' "
                        + "from tenant_management.tenant_business_database_binding b "
                        + "where b.tenant_id=? and b.database_identity=? and b.lifecycle_state='READY' "
                        + "and b.verified_schema_manifest_sha256=? "
                        + "and b.wallet_recharge_callback_credential_secret_reference is not null "
                        + "on conflict (recharge_id) do nothing",
                rechargeId, workspaceId.value(), amountMinor, binding.tenantId().value(),
                binding.databaseIdentity(), binding.verifiedSchemaManifestSha256());
        Route route = find(binding.tenantId(), workspaceId, rechargeId);
        if (route == null || !binding.databaseIdentity().equals(route.databaseIdentity())
                || route.amountMinor() != amountMinor || !"PEN".equals(route.currency())) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge route could not be durably registered");
        }
        return route;
    }

    public void bindProviderIntent(Route route, String providerPaymentIntentId) {
        Objects.requireNonNull(route, "Verified wallet recharge route is required");
        if (providerPaymentIntentId == null || providerPaymentIntentId.isBlank()
                || providerPaymentIntentId.length() > 255) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is invalid");
        }
        requireScope(route.tenantId(), route.workspaceId());
        int updated = centralJdbc.update("update tenant_management.wallet_recharge_provider_route "
                        + "set provider_payment_intent_id=?,status=case when status='PREPARING' "
                        + "then 'AWAITING_PAYMENT' else status end,updated_at=current_timestamp "
                        + "where recharge_id=? and tenant_id=? and workspace_id=? and database_identity=? "
                        + "and status in ('PREPARING','AWAITING_PAYMENT') "
                        + "and (provider_payment_intent_id is null or provider_payment_intent_id=?)",
                providerPaymentIntentId, route.rechargeId(), route.tenantId().value(), route.workspaceId().value(),
                route.databaseIdentity(), providerPaymentIntentId);
        if (updated != 1) throw new TenantBusinessDatabaseUnavailableException(
                "Tenant wallet recharge provider intent could not be bound");
    }

    public Route findForVerifiedEvent(UUID tenantId, UUID workspaceId, UUID rechargeId) {
        Objects.requireNonNull(tenantId, "Verified event Tenant id is required");
        Objects.requireNonNull(workspaceId, "Verified event Workspace id is required");
        Objects.requireNonNull(rechargeId, "Verified event recharge id is required");
        requireScope(tenantId, workspaceId);
        return selectRoute(tenantId, workspaceId, rechargeId);
    }

    public void markSucceeded(Route route) { markTerminal(route, "SUCCEEDED"); }
    public void markCancelled(Route route) { markTerminal(route, "CANCELLED"); }

    private void markTerminal(Route route, String status) {
        requireScope(route.tenantId(), route.workspaceId());
        int changed = centralJdbc.update("update tenant_management.wallet_recharge_provider_route set status=?, "
                        + "updated_at=current_timestamp,processed_at=current_timestamp "
                        + "where recharge_id=? and tenant_id=? and workspace_id=? "
                        + "and database_identity=? and provider_payment_intent_id=? and status='AWAITING_PAYMENT'",
                status, route.rechargeId(), route.tenantId().value(), route.workspaceId().value(),
                route.databaseIdentity(), route.providerPaymentIntentId());
        if (changed == 1) return;
        String persistedStatus = centralJdbc.query("select status from tenant_management.wallet_recharge_provider_route "
                        + "where recharge_id=? and tenant_id=? and workspace_id=? and database_identity=? "
                        + "and provider_payment_intent_id=?",
                (rs, row) -> rs.getString(1), route.rechargeId(), route.tenantId().value(),
                route.workspaceId().value(), route.databaseIdentity(), route.providerPaymentIntentId())
                .stream().findFirst().orElse(null);
        if (!status.equals(persistedStatus)) {
            throw new TenantBusinessDatabaseUnavailableException(
                    "Tenant wallet recharge provider route lost its terminal state transition");
        }
    }

    private Route find(TenantId tenantId, WorkspaceId workspaceId, UUID rechargeId) {
        return selectRoute(tenantId.value(), workspaceId.value(), rechargeId);
    }

    private Route selectRoute(UUID tenantId, UUID workspaceId, UUID rechargeId) {
        return centralJdbc.query("select r.recharge_id,r.tenant_id,r.workspace_id,r.database_identity,"
                        + "r.amount_minor,r.currency,r.provider_payment_intent_id,r.status,"
                        + "b.wallet_recharge_callback_credential_secret_reference,b.verified_schema_manifest_sha256 "
                        + "from tenant_management.wallet_recharge_provider_route r "
                        + "join tenant_management.tenant_business_database_binding b "
                        + "on b.tenant_id=r.tenant_id and b.database_identity=r.database_identity "
                        + "where r.tenant_id=? and r.workspace_id=? and r.recharge_id=? "
                        + "and b.lifecycle_state='READY'",
                (rs, row) -> new Route(rs.getObject("recharge_id", UUID.class),
                        new TenantId(rs.getObject("tenant_id", UUID.class)),
                        new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                        rs.getObject("database_identity", UUID.class), rs.getLong("amount_minor"),
                        rs.getString("currency"), rs.getString("provider_payment_intent_id"),
                        rs.getString("status"), rs.getString("wallet_recharge_callback_credential_secret_reference"),
                        rs.getString("verified_schema_manifest_sha256")),
                tenantId, workspaceId, rechargeId).stream().findFirst().orElse(null);
    }

    private static void requireScope(TenantId tenantId, WorkspaceId workspaceId) {
        requireScope(tenantId.value(), workspaceId.value());
    }

    private static void requireScope(UUID tenantId, UUID workspaceId) {
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null || !tenantId.equals(scope.tenantId()) || !workspaceId.equals(scope.workspaceId())) {
            throw new IllegalStateException("Wallet recharge route requires the matching verified Tenant scope");
        }
    }

    public record Route(UUID rechargeId, TenantId tenantId, WorkspaceId workspaceId, UUID databaseIdentity,
                        long amountMinor, String currency, String providerPaymentIntentId, String status,
                        String callbackCredentialSecretReference, String verifiedSchemaManifestSha256) {
        public Route {
            Objects.requireNonNull(rechargeId);
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(databaseIdentity);
            if (callbackCredentialSecretReference == null || callbackCredentialSecretReference.isBlank()) {
                throw new TenantBusinessDatabaseUnavailableException(
                        "Tenant wallet recharge callback credential is not provisioned");
            }
            if (verifiedSchemaManifestSha256 == null || !verifiedSchemaManifestSha256.matches("[0-9a-f]{64}")) {
                throw new TenantBusinessDatabaseUnavailableException(
                        "Tenant wallet recharge schema manifest is not verified");
            }
        }

        @Override public String toString() {
            return "WalletRechargeProviderRoute[rechargeId=" + rechargeId + ", tenantId=" + tenantId
                    + ", databaseIdentity=" + databaseIdentity + ", callbackCredentialSecretReference=<redacted>]";
        }
    }
}
