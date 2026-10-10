package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Central BC-01 opaque index for routing verified card-payment callbacks into one Tenant database. */
@Component
public final class JdbcPaymentProviderRouteRegistry {
    private final JdbcTemplate centralJdbc;

    public JdbcPaymentProviderRouteRegistry(JdbcTemplate centralJdbc) {
        this.centralJdbc = Objects.requireNonNull(centralJdbc, "Central route JdbcTemplate is required");
    }

    public Route registerPreparing(TenantBusinessDatabaseBinding binding, WorkspaceId workspaceId,
            UUID paymentId, long amountMinor, String currency) {
        Objects.requireNonNull(binding, "Verified Tenant binding is required");
        Objects.requireNonNull(paymentId, "Payment id is required");
        requireScope(binding.tenantId(), workspaceId);
        if (amountMinor < 1 || amountMinor > 9_999_999_999L || currency == null
                || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Payment provider route amount or currency is invalid");
        }
        UUID routeId = UUID.randomUUID();
        centralJdbc.update("insert into tenant_management.payment_provider_route "
                        + "(route_id,tenant_id,workspace_id,database_identity,payment_id,provider_code,amount_minor,currency) "
                        + "select ?,b.tenant_id,?,b.database_identity,?,'STRIPE',?,? "
                        + "from tenant_management.tenant_business_database_binding b "
                        + "join tenant_management.workspace w on w.tenant_id=b.tenant_id and w.id=? "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where b.tenant_id=? and b.database_identity=? and b.lifecycle_state='READY' "
                        + "and w.status='ACTIVE' and a.status='READY' "
                        + "and b.verified_schema_manifest_sha256=? "
                        + "and b.payment_callback_credential_secret_reference is not null "
                        + "on conflict (tenant_id,workspace_id,payment_id) do nothing",
                routeId, workspaceId.value(), paymentId, amountMinor, currency.toUpperCase(java.util.Locale.ROOT),
                workspaceId.value(), binding.tenantId().value(), binding.databaseIdentity(),
                binding.verifiedSchemaManifestSha256());
        Route route = findByPayment(binding.tenantId(), workspaceId, paymentId);
        if (route == null || !binding.databaseIdentity().equals(route.databaseIdentity())
                || route.amountMinor() != amountMinor || !currency.equalsIgnoreCase(route.currency())) {
            throw unavailable("Tenant payment provider route could not be durably registered");
        }
        return route;
    }

    public Route findForVerifiedIntent(String providerPaymentIntentId) {
        if (providerPaymentIntentId == null || providerPaymentIntentId.isBlank()
                || providerPaymentIntentId.length() > 255) {
            throw new IllegalArgumentException("Verified Stripe PaymentIntent id is invalid");
        }
        return centralJdbc.query("select r.route_id,r.tenant_id,r.workspace_id,r.database_identity,r.payment_id,r.provider_code,"
                        + "r.amount_minor,r.currency,r.provider_payment_intent_id,r.status,"
                        + "b.payment_callback_credential_secret_reference,b.verified_schema_manifest_sha256 "
                        + "from tenant_management.payment_provider_route r "
                        + "join tenant_management.tenant_business_database_binding b "
                        + "on b.tenant_id=r.tenant_id and b.database_identity=r.database_identity "
                        + "join tenant_management.workspace w on w.tenant_id=r.tenant_id and w.id=r.workspace_id "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where r.provider_code='STRIPE' and r.provider_payment_intent_id=? "
                        + "and b.lifecycle_state='READY' and w.status='ACTIVE' and a.status='READY'",
                (rs, row) -> route(rs), providerPaymentIntentId).stream().findFirst().orElse(null);
    }

    public Route findByRouteId(UUID routeId) {
        Objects.requireNonNull(routeId, "Opaque payment route id is required");
        return centralJdbc.query("select r.route_id,r.tenant_id,r.workspace_id,r.database_identity,r.payment_id,r.provider_code,"
                        + "r.amount_minor,r.currency,r.provider_payment_intent_id,r.status,"
                        + "b.payment_callback_credential_secret_reference,b.verified_schema_manifest_sha256 "
                        + "from tenant_management.payment_provider_route r "
                        + "join tenant_management.tenant_business_database_binding b "
                        + "on b.tenant_id=r.tenant_id and b.database_identity=r.database_identity "
                        + "join tenant_management.workspace w on w.tenant_id=r.tenant_id and w.id=r.workspace_id "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where r.route_id=? and b.lifecycle_state='READY' and w.status='ACTIVE' and a.status='READY'",
                (rs, row) -> route(rs), routeId).stream().findFirst().orElse(null);
    }

    public Route bindProviderIntent(Route route, String providerPaymentIntentId) {
        Objects.requireNonNull(route, "Verified payment route is required");
        if (providerPaymentIntentId == null || providerPaymentIntentId.isBlank()
                || providerPaymentIntentId.length() > 255) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is invalid");
        }
        requireScope(route.tenantId(), route.workspaceId());
        int changed = centralJdbc.update("update tenant_management.payment_provider_route "
                        + "set provider_payment_intent_id=?,updated_at=current_timestamp "
                        + "where route_id=? and tenant_id=? and workspace_id=? and database_identity=? "
                        + "and status in ('PREPARING','AWAITING_PAYMENT') "
                        + "and (provider_payment_intent_id is null or provider_payment_intent_id=?)",
                providerPaymentIntentId, route.routeId(), route.tenantId().value(), route.workspaceId().value(),
                route.databaseIdentity(), providerPaymentIntentId);
        if (changed != 1) throw unavailable("Tenant payment provider intent could not be bound");
        Route updated = findByRouteId(route.routeId());
        if (updated == null || !providerPaymentIntentId.equals(updated.providerPaymentIntentId())) {
            throw unavailable("Tenant payment provider intent binding could not be verified");
        }
        return updated;
    }

    /** Binds a provider id obtained from a signature-verified callback addressed by opaque route id. */
    public Route bindProviderIntentFromVerifiedEvent(Route route, String providerPaymentIntentId) {
        Objects.requireNonNull(route, "Verified payment route is required");
        if (providerPaymentIntentId == null || providerPaymentIntentId.isBlank()
                || providerPaymentIntentId.length() > 255) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is invalid");
        }
        int changed = centralJdbc.update("update tenant_management.payment_provider_route "
                        + "set provider_payment_intent_id=?,updated_at=current_timestamp "
                        + "where route_id=? and provider_code='STRIPE' and status in ('PREPARING','AWAITING_PAYMENT') "
                        + "and (provider_payment_intent_id is null or provider_payment_intent_id=?) "
                        + "and exists (select 1 from tenant_management.tenant_business_database_binding b "
                        + "join tenant_management.workspace w on w.tenant_id=b.tenant_id "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where b.tenant_id=tenant_management.payment_provider_route.tenant_id "
                        + "and b.database_identity=tenant_management.payment_provider_route.database_identity "
                        + "and w.id=tenant_management.payment_provider_route.workspace_id "
                        + "and b.lifecycle_state='READY' and w.status='ACTIVE' and a.status='READY')",
                providerPaymentIntentId, route.routeId(), providerPaymentIntentId);
        if (changed != 1) throw unavailable("Verified callback could not bind its opaque payment route");
        Route updated = findByRouteId(route.routeId());
        if (updated == null || !providerPaymentIntentId.equals(updated.providerPaymentIntentId())) {
            throw unavailable("Verified callback payment route binding could not be reloaded");
        }
        return updated;
    }

    public void activate(Route route, String providerPaymentIntentId) {
        Objects.requireNonNull(route, "Verified payment route is required");
        if (providerPaymentIntentId == null || providerPaymentIntentId.isBlank()) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is required");
        }
        requireScope(route.tenantId(), route.workspaceId());
        int changed = centralJdbc.update("update tenant_management.payment_provider_route "
                        + "set status='AWAITING_PAYMENT',updated_at=current_timestamp "
                        + "where route_id=? and tenant_id=? and workspace_id=? and database_identity=? "
                        + "and provider_payment_intent_id=? and status='PREPARING'",
                route.routeId(), route.tenantId().value(), route.workspaceId().value(), route.databaseIdentity(),
                providerPaymentIntentId);
        if (changed == 1) return;
        String current = centralJdbc.query("select status from tenant_management.payment_provider_route "
                        + "where route_id=? and provider_payment_intent_id=?",
                (rs, row) -> rs.getString(1), route.routeId(), providerPaymentIntentId)
                .stream().findFirst().orElse(null);
        if (current == null || !Set.of("AWAITING_PAYMENT", "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED").contains(current)) {
            throw unavailable("Tenant payment provider route activation failed");
        }
    }

    public void activateFromVerifiedEvent(Route route, String providerPaymentIntentId) {
        Objects.requireNonNull(route, "Verified payment route is required");
        int changed = centralJdbc.update("update tenant_management.payment_provider_route "
                        + "set status='AWAITING_PAYMENT',updated_at=current_timestamp "
                        + "where route_id=? and provider_code='STRIPE' and provider_payment_intent_id=? "
                        + "and status='PREPARING' and exists (select 1 "
                        + "from tenant_management.tenant_business_database_binding b "
                        + "join tenant_management.workspace w on w.tenant_id=b.tenant_id "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where b.tenant_id=tenant_management.payment_provider_route.tenant_id "
                        + "and b.database_identity=tenant_management.payment_provider_route.database_identity "
                        + "and w.id=tenant_management.payment_provider_route.workspace_id "
                        + "and b.lifecycle_state='READY' and w.status='ACTIVE' and a.status='READY')",
                route.routeId(), providerPaymentIntentId);
        if (changed == 1) return;
        String current = centralJdbc.query("select status from tenant_management.payment_provider_route "
                        + "where route_id=? and provider_payment_intent_id=?",
                (rs, row) -> rs.getString(1), route.routeId(), providerPaymentIntentId)
                .stream().findFirst().orElse(null);
        if (current == null || !Set.of("AWAITING_PAYMENT", "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED").contains(current)) {
            throw unavailable("Verified callback payment route could not be activated");
        }
    }

    public void markSucceeded(Route route) { markTerminal(route, "SUCCEEDED"); }
    public void markFailed(Route route) { markTerminal(route, "FAILED"); }
    public void markCancelled(Route route) { markTerminal(route, "CANCELLED"); }

    private void markTerminal(Route route, String status) {
        int changed = centralJdbc.update("update tenant_management.payment_provider_route set status=?,"
                        + "processed_at=current_timestamp,updated_at=current_timestamp "
                        + "where route_id=? and tenant_id=? and workspace_id=? and database_identity=? "
                        + "and provider_payment_intent_id=? and status='AWAITING_PAYMENT'",
                status, route.routeId(), route.tenantId().value(), route.workspaceId().value(),
                route.databaseIdentity(), route.providerPaymentIntentId());
        if (changed == 1) return;
        String current = centralJdbc.query("select status from tenant_management.payment_provider_route "
                        + "where route_id=? and provider_payment_intent_id=?",
                (rs, row) -> rs.getString(1), route.routeId(), route.providerPaymentIntentId())
                .stream().findFirst().orElse(null);
        if (!status.equals(current)) throw unavailable("Tenant payment provider route terminal transition failed");
    }

    private Route findByPayment(TenantId tenantId, WorkspaceId workspaceId, UUID paymentId) {
        return centralJdbc.query("select r.route_id,r.tenant_id,r.workspace_id,r.database_identity,r.payment_id,r.provider_code,"
                        + "r.amount_minor,r.currency,r.provider_payment_intent_id,r.status,"
                        + "b.payment_callback_credential_secret_reference,b.verified_schema_manifest_sha256 "
                        + "from tenant_management.payment_provider_route r "
                        + "join tenant_management.tenant_business_database_binding b "
                        + "on b.tenant_id=r.tenant_id and b.database_identity=r.database_identity "
                        + "join tenant_management.workspace w on w.tenant_id=r.tenant_id and w.id=r.workspace_id "
                        + "join tenant_management.tenant_business_database_workspace_anchor_task a "
                        + "on a.tenant_id=w.tenant_id and a.workspace_id=w.id "
                        + "where r.tenant_id=? and r.workspace_id=? and r.payment_id=? "
                        + "and b.lifecycle_state='READY' and w.status='ACTIVE' and a.status='READY'",
                (rs, row) -> route(rs), tenantId.value(), workspaceId.value(), paymentId)
                .stream().findFirst().orElse(null);
    }

    private static Route route(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Route(rs.getObject("route_id", UUID.class), new TenantId(rs.getObject("tenant_id", UUID.class)),
                new WorkspaceId(rs.getObject("workspace_id", UUID.class)),
                rs.getObject("database_identity", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getString("provider_code"),
                rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("provider_payment_intent_id"),
                rs.getString("status"), rs.getString("payment_callback_credential_secret_reference"),
                rs.getString("verified_schema_manifest_sha256"));
    }

    private static void requireScope(TenantId tenantId, WorkspaceId workspaceId) {
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.value().equals(current.tenantId())
                || !workspaceId.value().equals(current.workspaceId())) {
            throw new IllegalStateException("Tenant payment route requires matching verified scope");
        }
    }

    private static TenantBusinessDatabaseUnavailableException unavailable(String message) {
        return new TenantBusinessDatabaseUnavailableException(message);
    }

    public record Route(UUID routeId, TenantId tenantId, WorkspaceId workspaceId, UUID databaseIdentity,
                        UUID paymentId, String providerCode, long amountMinor, String currency, String providerPaymentIntentId,
                        String status, String callbackCredentialSecretReference,
                        String verifiedSchemaManifestSha256) {
        public Route {
            Objects.requireNonNull(routeId);
            Objects.requireNonNull(tenantId);
            Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(databaseIdentity);
            Objects.requireNonNull(paymentId);
            if (!"STRIPE".equals(providerCode)) throw unavailable("Payment provider route is not Stripe");
            if (callbackCredentialSecretReference == null || callbackCredentialSecretReference.isBlank()) {
                throw unavailable("Tenant payment callback credential is not provisioned");
            }
            if (verifiedSchemaManifestSha256 == null || !verifiedSchemaManifestSha256.matches("[0-9a-f]{64}")) {
                throw unavailable("Tenant payment schema manifest is not verified");
            }
        }

        @Override public String toString() {
            return "PaymentProviderRoute[routeId=" + routeId + ",tenantId=" + tenantId
                    + ",databaseIdentity=" + databaseIdentity
                    + ",callbackCredentialSecretReference=<redacted>]";
        }
    }
}
