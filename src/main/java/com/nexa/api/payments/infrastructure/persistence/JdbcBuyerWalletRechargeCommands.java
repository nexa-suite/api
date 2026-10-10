package com.nexa.api.payments.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.exception.PaymentIdempotencyPayloadConflictException;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeProviderEventProcessor;
import com.nexa.api.payments.application.publicapi.BuyerWalletRechargeTenantCommands;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-local recharge facts. Caller owns and must share the active Tenant JDBC transaction. */
public final class JdbcBuyerWalletRechargeCommands implements BuyerWalletRechargeTenantCommands {
    private static final String CURRENCY = "PEN";
    private static final String PROVIDER = "STRIPE";
    private static final String WORKER_ROLE = "nexa_wallet_recharge_worker";

    private final JdbcTemplate jdbc;
    private final CustomerAccountQuery customerAccounts;

    public JdbcBuyerWalletRechargeCommands(JdbcTemplate jdbc, CustomerAccountQuery customerAccounts) {
        this.jdbc = Objects.requireNonNull(jdbc, "Tenant wallet recharge JdbcTemplate is required");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "Tenant Customer Account query is required");
    }

    @Override
    public Claim prepare(CurrentAccessContext context, BigDecimal amountPEN, String idempotencyKey) {
        requireUserTransaction(context, PermissionKey.PAYMENT_CREATE);
        requireBuyer(context);
        Objects.requireNonNull(amountPEN, "PEN recharge amount is required");
        if (amountPEN.scale() != 2 || amountPEN.signum() <= 0
                || amountPEN.compareTo(new BigDecimal("999999.99")) > 0) {
            throw new IllegalArgumentException("PEN recharge amount must be positive with at most two decimals");
        }
        requireIdempotencyKey(idempotencyKey);
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        UUID buyerIdentityId = context.userId().value();
        CustomerAccountReference relationship = customerAccounts.findBuyerReference(
                        tenantId.toString(), workspaceId.toString(), context.membershipId().value().toString())
                .filter(CustomerAccountReference::active)
                .orElseThrow(() -> new IllegalArgumentException("An active Buyer account relationship is required"));
        if (relationship.id() == null || relationship.id().isBlank()) {
            throw new IllegalArgumentException("An active Buyer account relationship is required");
        }

        UUID rechargeId = UUID.randomUUID();
        jdbc.update("insert into payments.buyer_wallet_recharge "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,buyer_membership_id,provider_code,amount_minor,"
                        + "currency,idempotency_key,status,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,?,?,?,'PREPARING',current_timestamp,current_timestamp) "
                        + "on conflict (tenant_id,workspace_id,buyer_identity_id,idempotency_key) do nothing",
                rechargeId, tenantId, workspaceId, buyerIdentityId, context.membershipId().value(), PROVIDER,
                minor(amountPEN), CURRENCY, idempotencyKey);
        Claim claim = findClaim(tenantId, workspaceId, buyerIdentityId, idempotencyKey, true);
        if (claim == null) throw new IllegalStateException("Buyer wallet recharge claim could not be loaded");
        if (claim.amountMinor() != minor(amountPEN) || !CURRENCY.equals(claim.currency())) {
            throw new PaymentIdempotencyPayloadConflictException();
        }
        String existingMembership = jdbc.queryForObject("select buyer_membership_id::text "
                        + "from payments.buyer_wallet_recharge where tenant_id=? and workspace_id=? and id=?",
                String.class, tenantId, workspaceId, claim.rechargeId());
        if (!context.membershipId().value().toString().equals(existingMembership)) {
            throw new PaymentIdempotencyPayloadConflictException();
        }
        return claim;
    }

    @Override
    public Claim bindProviderIntent(CurrentAccessContext context, UUID rechargeId, String providerPaymentIntentId) {
        requireUserTransaction(context, PermissionKey.PAYMENT_CREATE);
        requireBuyer(context);
        requireProviderIntentId(providerPaymentIntentId);
        Claim current = findBuyerClaim(context, rechargeId, true);
        if (current == null) throw new IllegalArgumentException("Buyer wallet recharge was not found");
        if (current.providerPaymentIntentId() != null
                && !current.providerPaymentIntentId().equals(providerPaymentIntentId)) {
            throw new IllegalStateException("Buyer wallet recharge PaymentIntent binding changed");
        }
        jdbc.update("update payments.buyer_wallet_recharge set provider_payment_intent_id=?, "
                        + "status=case when status='PREPARING' then 'AWAITING_PAYMENT' else status end, "
                        + "updated_at=current_timestamp where tenant_id=? and workspace_id=? and id=? "
                        + "and (provider_payment_intent_id is null or provider_payment_intent_id=?)",
                providerPaymentIntentId, tenant(context), workspace(context), rechargeId, providerPaymentIntentId);
        return findBuyerClaim(context, rechargeId, false);
    }

    @Override
    public BuyerWalletModels.RechargeView get(CurrentAccessContext context, UUID rechargeId) {
        requireUserTransaction(context, PermissionKey.PAYMENT_READ);
        requireBuyer(context);
        UUID tenantId = tenant(context);
        UUID workspaceId = workspace(context);
        return jdbc.query("select id,status,amount_minor,currency,provider_code,provider_payment_intent_id,"
                        + "created_at,updated_at,completed_at from payments.buyer_wallet_recharge "
                        + "where tenant_id=? and workspace_id=? and buyer_identity_id=? and buyer_membership_id=? and id=?",
                (rs, row) -> new BuyerWalletModels.RechargeView(rs.getObject("id", UUID.class),
                        BuyerWalletModels.RechargeStatus.valueOf(rs.getString("status")),
                        money(rs.getLong("amount_minor")), rs.getString("currency"), rs.getString("provider_code"),
                        rs.getString("provider_payment_intent_id"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at")), instant(rs.getTimestamp("completed_at"))),
                tenantId, workspaceId, context.userId().value(), context.membershipId().value(), rechargeId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Buyer wallet recharge was not found"));
    }

    @Override
    public BuyerWalletRechargeProviderEventProcessor.Outcome processVerifiedProviderEvent(
            UUID tenantId, UUID workspaceId, BuyerWalletRechargeProviderEventProcessor.VerifiedEvent event) {
        requireSystemWorkerTransaction(tenantId, workspaceId);
        Objects.requireNonNull(event, "Verified provider event is required");
        requireBounded(event.eventId(), 160, "Stripe event id");
        requireBounded(event.eventType(), 160, "Stripe event type");
        if (event.rechargeId() == null) throw new IllegalArgumentException("Wallet recharge id is required");

        ProcessedEvent previous = jdbc.query("select outcome from payments.buyer_wallet_recharge_processed_event "
                        + "where tenant_id=? and workspace_id=? and provider_code=? and provider_event_id=?",
                (rs, row) -> new ProcessedEvent(rs.getString(1)), tenantId, workspaceId, PROVIDER, event.eventId())
                .stream().findFirst().orElse(null);
        if (previous != null) return outcome(previous.outcome());

        RechargeRow recharge = jdbc.query("select id,buyer_identity_id,provider_code,amount_minor,currency,"
                        + "provider_payment_intent_id,status from payments.buyer_wallet_recharge "
                        + "where tenant_id=? and workspace_id=? and id=? for update",
                (rs, row) -> new RechargeRow(rs.getObject("id", UUID.class), rs.getObject("buyer_identity_id", UUID.class),
                        rs.getString("provider_code"), rs.getLong("amount_minor"), rs.getString("currency"),
                        rs.getString("provider_payment_intent_id"), rs.getString("status")),
                tenantId, workspaceId, event.rechargeId()).stream().findFirst().orElse(null);

        if (recharge == null) {
            return recordEvent(tenantId, workspaceId, event, "REJECTED", "RECHARGE_NOT_FOUND");
        }
        String rejection = mismatchReason(tenantId, workspaceId, event, recharge);
        if (rejection != null) return recordEvent(tenantId, workspaceId, event, "REJECTED", rejection);

        if ("payment_intent.canceled".equals(event.eventType())
                || "canceled".equalsIgnoreCase(event.paymentStatus())) {
            if ("PREPARING".equals(recharge.status()) || "AWAITING_PAYMENT".equals(recharge.status())) {
                if (jdbc.update("update payments.buyer_wallet_recharge set status='CANCELLED',updated_at=current_timestamp,"
                                + "completed_at=current_timestamp where tenant_id=? and workspace_id=? and id=? "
                                + "and status in ('PREPARING','AWAITING_PAYMENT')",
                        tenantId, workspaceId, recharge.id()) != 1) {
                    throw new IllegalStateException("Buyer wallet recharge cancellation lost its state transition");
                }
                return recordEvent(tenantId, workspaceId, event, "CANCELLED", null);
            }
            if ("CANCELLED".equals(recharge.status())) {
                return recordEvent(tenantId, workspaceId, event, "CANCELLED", null);
            }
            return recordEvent(tenantId, workspaceId, event, "IGNORED", null);
        }
        if (!("payment_intent.succeeded".equals(event.eventType())
                && "succeeded".equalsIgnoreCase(event.paymentStatus()))) {
            return recordEvent(tenantId, workspaceId, event, "IGNORED", null);
        }
        if (!"AWAITING_PAYMENT".equals(recharge.status())) {
            return recordEvent(tenantId, workspaceId, event, "IGNORED", null);
        }

        jdbc.update("insert into payments.buyer_wallet_account "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency) values (?,?,?,?,?) "
                        + "on conflict (tenant_id,workspace_id,buyer_identity_id,currency) do nothing",
                UUID.randomUUID(), tenantId, workspaceId, recharge.buyerIdentityId(), CURRENCY);
        AccountRow account = jdbc.query("select id from payments.buyer_wallet_account "
                        + "where tenant_id=? and workspace_id=? and buyer_identity_id=? and currency=? for update",
                (rs, row) -> new AccountRow(rs.getObject(1, UUID.class)), tenantId, workspaceId,
                recharge.buyerIdentityId(), CURRENCY).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Buyer wallet account could not be initialized"));
        Instant now = Instant.now();
        if (jdbc.update("update payments.buyer_wallet_account set posted_balance=posted_balance+?, "
                        + "version=version+1,updated_at=? where tenant_id=? and workspace_id=? and id=?",
                money(recharge.amountMinor()), Timestamp.from(now), tenantId, workspaceId, account.id()) != 1) {
            throw new IllegalStateException("Buyer wallet balance could not be credited");
        }
        jdbc.update("insert into payments.buyer_wallet_ledger_entry "
                        + "(id,tenant_id,workspace_id,buyer_identity_id,currency,reservation_id,entry_type,amount_delta,"
                        + "source_id,idempotency_key,provider_code,provider_event_id,occurred_at) "
                        + "values (?,?,?,?,?,null,'PROVIDER_RECHARGE',?,?,?,?,?,?)",
                UUID.randomUUID(), tenantId, workspaceId, recharge.buyerIdentityId(), CURRENCY,
                money(recharge.amountMinor()), recharge.id(), "wallet-recharge:" + recharge.id(), PROVIDER,
                event.eventId(), Timestamp.from(now));
        if (jdbc.update("update payments.buyer_wallet_recharge set status='SUCCEEDED',updated_at=?,completed_at=? "
                        + "where tenant_id=? and workspace_id=? and id=? and status='AWAITING_PAYMENT'",
                Timestamp.from(now), Timestamp.from(now), tenantId, workspaceId, recharge.id()) != 1) {
            throw new IllegalStateException("Buyer wallet recharge success lost its state transition");
        }
        return recordEvent(tenantId, workspaceId, event, "PROCESSED", null);
    }

    private String mismatchReason(UUID tenantId, UUID workspaceId,
            BuyerWalletRechargeProviderEventProcessor.VerifiedEvent event, RechargeRow recharge) {
        if (!tenantId.equals(event.tenantId()) || !workspaceId.equals(event.workspaceId())) return "SCOPE_MISMATCH";
        if (!PROVIDER.equals(recharge.providerCode())) return "PROVIDER_MISMATCH";
        if (recharge.providerPaymentIntentId() == null
                || !recharge.providerPaymentIntentId().equals(event.paymentIntentId())) return "INTENT_MISMATCH";
        if (event.amountMinor() == null || event.amountMinor() != recharge.amountMinor()) return "AMOUNT_MISMATCH";
        if (event.currency() == null || !CURRENCY.equalsIgnoreCase(event.currency())
                || !CURRENCY.equals(recharge.currency())) return "CURRENCY_MISMATCH";
        return null;
    }

    private BuyerWalletRechargeProviderEventProcessor.Outcome recordEvent(UUID tenantId, UUID workspaceId,
            BuyerWalletRechargeProviderEventProcessor.VerifiedEvent event, String outcome, String reason) {
        jdbc.update("insert into payments.buyer_wallet_recharge_processed_event "
                        + "(id,tenant_id,workspace_id,recharge_id,provider_code,provider_event_id,"
                        + "provider_payment_intent_id,event_type,payment_status,amount_minor,currency,outcome,reason_code,processed_at) "
                        + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,current_timestamp) on conflict "
                        + "(tenant_id,workspace_id,provider_code,provider_event_id) do nothing",
                UUID.randomUUID(), tenantId, workspaceId, event.rechargeId(), PROVIDER, event.eventId(),
                boundedOrNull(event.paymentIntentId(), 255), event.eventType(), boundedOrNull(event.paymentStatus(), 32),
                event.amountMinor(), normalizedCurrency(event.currency()), outcome, reason);
        String persistedOutcome = jdbc.query("select outcome from payments.buyer_wallet_recharge_processed_event "
                        + "where tenant_id=? and workspace_id=? and provider_code=? and provider_event_id=?",
                (rs, row) -> rs.getString(1), tenantId, workspaceId, PROVIDER, event.eventId())
                .stream().findFirst().orElseThrow(() ->
                        new IllegalStateException("Buyer wallet provider event evidence could not be loaded"));
        return outcome(persistedOutcome);
    }

    private static BuyerWalletRechargeProviderEventProcessor.Outcome outcome(String storedOutcome) {
        return switch (storedOutcome) {
            case "APPLIED", "PROCESSED" -> BuyerWalletRechargeProviderEventProcessor.Outcome.PROCESSED;
            case "CANCELLED" -> BuyerWalletRechargeProviderEventProcessor.Outcome.CANCELLED;
            case "IGNORED" -> BuyerWalletRechargeProviderEventProcessor.Outcome.IGNORED;
            case "REJECTED" -> BuyerWalletRechargeProviderEventProcessor.Outcome.REJECTED;
            default -> throw new IllegalStateException("Stored wallet recharge event outcome is invalid");
        };
    }

    private Claim findBuyerClaim(CurrentAccessContext context, UUID rechargeId, boolean lock) {
        return jdbc.query("select id,amount_minor,currency,status,provider_payment_intent_id,created_at "
                        + "from payments.buyer_wallet_recharge where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and buyer_membership_id=? and id=?" + (lock ? " for update" : ""),
                (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getLong("amount_minor"),
                        rs.getString("currency"), BuyerWalletModels.RechargeStatus.valueOf(rs.getString("status")),
                        rs.getString("provider_payment_intent_id"), instant(rs.getTimestamp("created_at"))),
                tenant(context), workspace(context), context.userId().value(), context.membershipId().value(), rechargeId)
                .stream().findFirst().orElse(null);
    }

    private Claim findClaim(UUID tenantId, UUID workspaceId, UUID buyerIdentityId, String key, boolean lock) {
        return jdbc.query("select id,amount_minor,currency,status,provider_payment_intent_id,created_at "
                        + "from payments.buyer_wallet_recharge where tenant_id=? and workspace_id=? "
                        + "and buyer_identity_id=? and idempotency_key=?" + (lock ? " for update" : ""),
                (rs, row) -> new Claim(rs.getObject("id", UUID.class), rs.getLong("amount_minor"),
                        rs.getString("currency"), BuyerWalletModels.RechargeStatus.valueOf(rs.getString("status")),
                        rs.getString("provider_payment_intent_id"), instant(rs.getTimestamp("created_at"))),
                tenantId, workspaceId, buyerIdentityId, key).stream().findFirst().orElse(null);
    }

    private void requireUserTransaction(CurrentAccessContext context, PermissionKey permission) {
        Objects.requireNonNull(context, "Verified Buyer access context is required");
        context.requirePermission(permission);
        requireBuyer(context);
        requireTenantTransaction(tenant(context), workspace(context));
    }

    private static void requireBuyer(CurrentAccessContext context) {
        if (!context.hasRoleCode("BUYER")) throw new IllegalArgumentException("An active Buyer membership is required");
    }

    private void requireSystemWorkerTransaction(UUID tenantId, UUID workspaceId) {
        requireTenantTransaction(tenantId, workspaceId);
        String currentRole = jdbc.queryForObject("select current_user", String.class);
        if (!WORKER_ROLE.equals(currentRole)) {
            throw new IllegalStateException("Wallet recharge callbacks require the dedicated Tenant worker role");
        }
    }

    private void requireTenantTransaction(UUID tenantId, UUID workspaceId) {
        RlsRequestScope.Scope scope = RlsRequestScope.current();
        if (scope == null || !tenantId.equals(scope.tenantId()) || !workspaceId.equals(scope.workspaceId())) {
            throw new IllegalStateException("Buyer wallet recharge requires matching verified Tenant and Workspace scope");
        }
        DataSource dataSource = jdbc.getDataSource();
        if (dataSource == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(dataSource)) {
            throw new IllegalStateException("Buyer wallet recharge requires its active Tenant database transaction");
        }
    }

    private static long minor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static BigDecimal money(long minor) { return BigDecimal.valueOf(minor, 2); }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static String normalizedCurrency(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.toUpperCase(java.util.Locale.ROOT);
        return normalized.matches("[A-Z]{3}") ? normalized : null;
    }

    private static String boundedOrNull(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static UUID tenant(CurrentAccessContext context) { return context.tenantId().value(); }
    private static UUID workspace(CurrentAccessContext context) { return context.workspaceId().value(); }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 160) {
            throw new IllegalArgumentException("A bounded idempotency key is required");
        }
    }

    private static void requireProviderIntentId(String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("Stripe PaymentIntent id is invalid");
        }
    }

    private static void requireBounded(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }

    private record RechargeRow(UUID id, UUID buyerIdentityId, String providerCode, long amountMinor,
                               String currency, String providerPaymentIntentId, String status) { }
    private record AccountRow(UUID id) { }
    private record ProcessedEvent(String outcome) { }
}
