package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.application.exception.NotificationOperationException;
import com.nexa.api.notifications.application.port.out.PushSubscriptionPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** JDBC adapter for subscription routing; only token hashes are persisted. */
@Repository
@Profile("!test")
public class JdbcPushSubscriptionAdapter implements PushSubscriptionPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcPushSubscriptionAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PushSubscription register(RegisterRequest request) {
        require(request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.userId(),
                request.installationId(), request.platform(), request.tokenHash(), request.actorMembershipId(),
                request.idempotencyKey(), request.requestHash(), request.now());
        lock(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "REGISTER", request.idempotencyKey());
        lockInstallation(request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.installationId());
        Idempotency prior = findIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), "REGISTER", request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.hash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.subscriptionId());
        }
        ExistingSubscription existing = jdbc.query("select id,status from notifications.push_subscription where tenant_id=? and workspace_id=? and recipient_membership_id=? and installation_id=? for update",
                (rs, row) -> new ExistingSubscription(rs.getObject("id", UUID.class), rs.getString("status")),
                request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.installationId())
                .stream().findFirst().orElse(null);
        UUID id;
        if (existing == null) {
            id = UUID.randomUUID();
            jdbc.update("insert into notifications.push_subscription(id,tenant_id,workspace_id,recipient_membership_id,user_id,surface,installation_id,platform,provider_token_hash,status,created_at,updated_at,last_seen_at,version) values (?,?,?,?,?,?,?,?,?,'ENABLED',?,?,?,0)",
                    id, request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.userId(), request.surface(),
                    request.installationId(), request.platform(), request.tokenHash(), Timestamp.from(request.now()), Timestamp.from(request.now()), Timestamp.from(request.now()));
        } else {
            if ("UNREGISTERED".equals(existing.status())) {
                throw new NotificationOperationException("PUSH_SUBSCRIPTION_CONFLICT", false);
            }
            id = existing.id();
            jdbc.update("update notifications.push_subscription set user_id=?,surface=?,platform=?,provider_token_hash=?,status='ENABLED',updated_at=?,last_seen_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=?",
                    request.userId(), request.surface(), request.platform(), request.tokenHash(), Timestamp.from(request.now()), Timestamp.from(request.now()),
                    request.tenantId(), request.workspaceId(), id);
        }
        jdbc.update("insert into notifications.push_subscription_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,subscription_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), "REGISTER", request.idempotencyKey(), request.requestHash(), id, Timestamp.from(request.now()));
        return load(request.tenantId(), request.workspaceId(), id);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PushSubscription disable(DisableRequest request) {
        require(request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.subscriptionId(),
                request.operation(), request.actorMembershipId(), request.idempotencyKey(), request.requestHash(), request.now());
        lock(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        Idempotency prior = findIdempotency(request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey());
        if (prior != null) {
            ensureHash(prior.hash(), request.requestHash());
            return load(request.tenantId(), request.workspaceId(), prior.subscriptionId());
        }
        int changed = jdbc.update("update notifications.push_subscription set status=?,updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and recipient_membership_id=? and id=?",
                "UNREGISTER".equals(request.operation()) ? "UNREGISTERED" : "DISABLED", Timestamp.from(request.now()),
                request.tenantId(), request.workspaceId(), request.recipientMembershipId(), request.subscriptionId());
        if (changed != 1) throw new NotificationOperationException("PUSH_SUBSCRIPTION_NOT_FOUND", true);
        jdbc.update("insert into notifications.push_subscription_command_idempotency(tenant_id,workspace_id,actor_membership_id,operation,idempotency_key,request_hash,subscription_id,created_at) values (?,?,?,?,?,?,?,?)",
                request.tenantId(), request.workspaceId(), request.actorMembershipId(), request.operation(), request.idempotencyKey(), request.requestHash(), request.subscriptionId(), Timestamp.from(request.now()));
        return load(request.tenantId(), request.workspaceId(), request.subscriptionId());
    }

    @Override
    public List<PushSubscription> activeForRecipient(UUID tenantId, UUID workspaceId, UUID recipientMembershipId) {
        return jdbc.query("select id,recipient_membership_id,installation_id,platform,surface,status,created_at,updated_at,version from notifications.push_subscription where tenant_id=? and workspace_id=? and recipient_membership_id=? and status='ENABLED' order by id",
                (rs, row) -> subscription(rs), tenantId, workspaceId, recipientMembershipId);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryClaim claimDelivery(UUID tenantId, UUID workspaceId, UUID subscriptionId, String eventId,
                                       String deliveryKey, int maxAttempts, Instant now, Instant leaseUntil) {
        require(tenantId, workspaceId, subscriptionId, eventId, deliveryKey, now, leaseUntil);
        if (maxAttempts < 1 || !leaseUntil.isAfter(now)) {
            throw new IllegalArgumentException("Push delivery retry bounds are invalid");
        }
        UUID eventUuid = uuid(eventId);
        UUID claimToken = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into notifications.push_delivery_claim
                    (tenant_id,workspace_id,subscription_id,event_id,delivery_key,status,claim_token,lease_until,created_at,updated_at)
                values (?,?,?,?,?,'CLAIMED',?,?,?,?)
                on conflict (tenant_id,workspace_id,subscription_id,event_id) do nothing
                """, tenantId, workspaceId, subscriptionId, eventUuid, deliveryKey, claimToken,
                Timestamp.from(leaseUntil), Timestamp.from(now), Timestamp.from(now));

        ClaimState claim = jdbc.query("select status,claim_token,lease_until from notifications.push_delivery_claim "
                        + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? for update",
                (rs, row) -> new ClaimState(rs.getString("status"), rs.getObject("claim_token", UUID.class),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()),
                tenantId, workspaceId, subscriptionId, eventUuid).stream().findFirst()
                .orElseThrow(() -> new NotificationOperationException("PUSH_DELIVERY_CLAIM_UNAVAILABLE", true));

        Boolean sent = jdbc.queryForObject("select exists(select 1 from notifications.push_delivery_attempt where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? and status='SENT')",
                Boolean.class, tenantId, workspaceId, subscriptionId, eventUuid);
        if (Boolean.TRUE.equals(sent)) {
            jdbc.update("update notifications.push_delivery_claim set status='SENT',claim_token=null,lease_until=null,updated_at=? where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? and status<>'SENT'",
                    Timestamp.from(now), tenantId, workspaceId, subscriptionId, eventUuid);
            return new DeliveryClaim(DeliveryClaimStatus.ALREADY_SENT, null, 0);
        }
        if ("SENT".equals(claim.status())) return new DeliveryClaim(DeliveryClaimStatus.ALREADY_SENT, null, 0);
        if ("DEAD_LETTER".equals(claim.status())) return new DeliveryClaim(DeliveryClaimStatus.DEAD_LETTER, null, 0);

        Integer attempts = jdbc.queryForObject("select coalesce(max(attempt_number),0) from notifications.push_delivery_attempt "
                        + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?",
                Integer.class, tenantId, workspaceId, subscriptionId, eventUuid);
        int nextAttempt = attempts + 1;
        if (attempts >= maxAttempts) {
            jdbc.update("update notifications.push_delivery_claim set status='DEAD_LETTER',claim_token=null,lease_until=null,updated_at=? "
                            + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? and status='CLAIMED'",
                    Timestamp.from(now), tenantId, workspaceId, subscriptionId, eventUuid);
            return new DeliveryClaim(DeliveryClaimStatus.DEAD_LETTER, null, 0);
        }
        if (inserted == 1) return new DeliveryClaim(DeliveryClaimStatus.CLAIMED, claimToken, nextAttempt);
        if (claim.leaseUntil() != null && claim.leaseUntil().isAfter(now)) {
            return new DeliveryClaim(DeliveryClaimStatus.BUSY, null, 0);
        }

        int reclaimed = jdbc.update("""
                update notifications.push_delivery_claim
                   set status='CLAIMED',claim_token=?,lease_until=?,updated_at=?
                 where tenant_id=? and workspace_id=? and subscription_id=? and event_id=?
                   and status='CLAIMED'
                """, claimToken, Timestamp.from(leaseUntil), Timestamp.from(now),
                tenantId, workspaceId, subscriptionId, eventUuid);
        return reclaimed == 1
                ? new DeliveryClaim(DeliveryClaimStatus.CLAIMED, claimToken, nextAttempt)
                : new DeliveryClaim(DeliveryClaimStatus.BUSY, null, 0);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryCompletion completeDelivery(DeliveryAttempt attempt, UUID claimToken, int attemptNumber,
            int maxAttempts, boolean retryable, Instant retryAt) {
        require(attempt.tenantId(), attempt.workspaceId(), attempt.subscriptionId(), attempt.eventId(),
                attempt.eventType(), attempt.status(), attempt.providerCode(), attempt.now(), claimToken);
        if (attemptNumber < 1 || maxAttempts < 1 || attemptNumber > maxAttempts
                || (retryable && (retryAt == null || !retryAt.isAfter(attempt.now())))) {
            throw new IllegalArgumentException("Push delivery completion is invalid");
        }
        UUID eventUuid = uuid(attempt.eventId());
        lockDeliveryAttempt(attempt.tenantId(), attempt.workspaceId(), attempt.subscriptionId(), eventUuid);
        ClaimState claim = jdbc.query("select status,claim_token,lease_until from notifications.push_delivery_claim "
                        + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? for update",
                (rs, row) -> new ClaimState(rs.getString("status"), rs.getObject("claim_token", UUID.class),
                        rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant()),
                attempt.tenantId(), attempt.workspaceId(), attempt.subscriptionId(), eventUuid).stream().findFirst()
                .orElseThrow(() -> new NotificationOperationException("PUSH_DELIVERY_CLAIM_LOST", true));
        if ("SENT".equals(claim.status())) return DeliveryCompletion.SENT;
        if ("DEAD_LETTER".equals(claim.status())) return DeliveryCompletion.DEAD_LETTER;
        if (!"CLAIMED".equals(claim.status()) || !claimToken.equals(claim.claimToken())
                || claim.leaseUntil() == null) {
            throw new NotificationOperationException("PUSH_DELIVERY_CLAIM_LOST", true);
        }
        Instant checkedAt = jdbc.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
        if (!claim.leaseUntil().isAfter(checkedAt)) {
            throw new NotificationOperationException("PUSH_DELIVERY_CLAIM_LOST", true);
        }

        Integer completedAttempts = jdbc.queryForObject("select coalesce(max(attempt_number),0) "
                        + "from notifications.push_delivery_attempt where tenant_id=? and workspace_id=? "
                        + "and subscription_id=? and event_id=?",
                Integer.class, attempt.tenantId(), attempt.workspaceId(), attempt.subscriptionId(), eventUuid);
        if (completedAttempts + 1 != attemptNumber) {
            throw new NotificationOperationException("PUSH_DELIVERY_ATTEMPT_SEQUENCE_CHANGED", true);
        }
        jdbc.update("insert into notifications.push_delivery_attempt(id,tenant_id,workspace_id,subscription_id,event_id,event_type,status,provider_code,error,attempt_number,created_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), attempt.tenantId(), attempt.workspaceId(), attempt.subscriptionId(), eventUuid,
                attempt.eventType(), attempt.status(), attempt.providerCode(), attempt.error(), attemptNumber,
                Timestamp.from(attempt.now()));

        boolean sent = "SENT".equals(attempt.status());
        boolean retryScheduled = !sent && retryable && attemptNumber < maxAttempts;
        String status = sent ? "SENT" : retryScheduled ? "CLAIMED" : "DEAD_LETTER";
        Timestamp nextLease = retryScheduled ? Timestamp.from(retryAt) : null;
        int changed = jdbc.update("update notifications.push_delivery_claim set status=?,claim_token=null,lease_until=?,updated_at=? "
                        + "where tenant_id=? and workspace_id=? and subscription_id=? and event_id=? "
                        + "and status='CLAIMED' and claim_token=?",
                status, nextLease, Timestamp.from(attempt.now()), attempt.tenantId(), attempt.workspaceId(),
                attempt.subscriptionId(), eventUuid, claimToken);
        if (changed != 1) throw new NotificationOperationException("PUSH_DELIVERY_CLAIM_LOST", true);
        return sent ? DeliveryCompletion.SENT : retryScheduled
                ? DeliveryCompletion.RETRY_SCHEDULED : DeliveryCompletion.DEAD_LETTER;
    }

    private PushSubscription load(UUID tenant, UUID workspace, UUID id) {
        return jdbc.query("select id,recipient_membership_id,installation_id,platform,surface,status,created_at,updated_at,version from notifications.push_subscription where tenant_id=? and workspace_id=? and id=?",
                (rs, row) -> subscription(rs), tenant, workspace, id).stream().findFirst()
                .orElseThrow(() -> new NotificationOperationException("PUSH_SUBSCRIPTION_NOT_FOUND", true));
    }

    private Idempotency findIdempotency(UUID tenant, UUID workspace, UUID actor, String operation, String key) {
        return jdbc.query("select request_hash,subscription_id from notifications.push_subscription_command_idempotency where tenant_id=? and workspace_id=? and actor_membership_id=? and operation=? and idempotency_key=? for update",
                (rs, row) -> new Idempotency(rs.getString(1), rs.getObject(2, UUID.class)), tenant, workspace, actor, operation, key)
                .stream().findFirst().orElse(null);
    }

    private void lock(UUID tenant, UUID workspace, UUID actor, String operation, String key) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|push|" + actor + "|" + operation + "|" + key);
    }

    private void lockInstallation(UUID tenant, UUID workspace, UUID recipient, String installation) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|push-installation|" + recipient + "|" + installation);
    }

    private void lockDeliveryAttempt(UUID tenant, UUID workspace, UUID subscription, UUID eventId) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?,0))", (org.springframework.jdbc.core.ResultSetExtractor<Void>) rs -> null,
                tenant + "|" + workspace + "|push-delivery|" + subscription + "|" + eventId);
    }

    private static PushSubscription subscription(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PushSubscription(rs.getObject("id", UUID.class), rs.getObject("recipient_membership_id", UUID.class),
                rs.getString("installation_id"), rs.getString("platform"), rs.getString("surface"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
    private static void ensureHash(String stored, String actual) { if (!Objects.equals(stored, actual)) throw new NotificationOperationException("IDEMPOTENCY_PAYLOAD_CONFLICT", false); }
    private static void require(Object... values) { for (Object value : values) if (value == null) throw new IllegalArgumentException("Push subscription request is incomplete"); }
    private record ExistingSubscription(UUID id, String status) { }
    private record ClaimState(String status, UUID claimToken, Instant leaseUntil) { }
    private record Idempotency(String hash, UUID subscriptionId) { }
}
