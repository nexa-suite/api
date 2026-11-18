package com.nexa.api.notifications.infrastructure.persistence;

import com.nexa.api.notifications.infrastructure.FcmPushProvider;
import com.nexa.api.notifications.infrastructure.PushDestinationCipher;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Tenant-session resolver and compare-and-set invalidator for encrypted FCM destinations. */
public final class JdbcFcmPushDestinationStore implements FcmPushProvider.DestinationResolver,
        FcmPushProvider.InvalidDestinationHandler {
    private final JdbcTemplate jdbc;
    private final PushDestinationCipher cipher;

    public JdbcFcmPushDestinationStore(JdbcTemplate tenantJdbc, PushDestinationCipher cipher) {
        this.jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        this.cipher = Objects.requireNonNull(cipher, "Push destination cipher is required");
    }

    @Override
    public String resolve(UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        requireScope(tenantId, workspaceId, subscriptionId);
        return jdbc.query("""
                        select provider_destination_ciphertext,provider_destination_key_version
                          from notifications.push_subscription
                         where tenant_id=? and workspace_id=? and id=? and status='ENABLED'
                           and provider_destination_ciphertext is not null
                           and provider_destination_key_version is not null
                        """, (rs, row) -> cipher.decrypt(rs.getString("provider_destination_ciphertext"),
                        rs.getString("provider_destination_key_version"), tenantId, workspaceId, subscriptionId),
                tenantId, workspaceId, subscriptionId).stream().findFirst().orElse(null);
    }

    @Override
    public void invalidate(UUID tenantId, UUID workspaceId, UUID subscriptionId, String attemptedDestination) {
        requireScope(tenantId, workspaceId, subscriptionId);
        if (attemptedDestination == null || attemptedDestination.isBlank()) return;
        List<StoredDestination> stored = jdbc.query("""
                        select provider_destination_ciphertext,provider_destination_key_version,version
                          from notifications.push_subscription
                         where tenant_id=? and workspace_id=? and id=? and status='ENABLED'
                           and provider_destination_ciphertext is not null
                           and provider_destination_key_version is not null
                        """, (rs, row) -> new StoredDestination(rs.getString("provider_destination_ciphertext"),
                        rs.getString("provider_destination_key_version"), rs.getLong("version")),
                tenantId, workspaceId, subscriptionId);
        if (stored.isEmpty()) return;
        StoredDestination current = stored.getFirst();
        String currentDestination = cipher.decrypt(current.ciphertext(), current.keyVersion(), tenantId,
                workspaceId, subscriptionId);
        if (!sameDestination(currentDestination, attemptedDestination)) return;
        jdbc.update("""
                        update notifications.push_subscription
                           set status='UNREGISTERED',provider_destination_ciphertext=null,
                               provider_destination_key_version=null,updated_at=?,version=version+1
                         where tenant_id=? and workspace_id=? and id=? and status='ENABLED'
                           and version=? and provider_destination_ciphertext=?
                           and provider_destination_key_version=?
                        """, Timestamp.from(Instant.now()), tenantId, workspaceId, subscriptionId,
                current.version(), current.ciphertext(), current.keyVersion());
    }

    private static boolean sameDestination(String current, String attempted) {
        return MessageDigest.isEqual(current.getBytes(StandardCharsets.UTF_8), attempted.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireScope(UUID tenantId, UUID workspaceId, UUID subscriptionId) {
        if (tenantId == null || workspaceId == null || subscriptionId == null) {
            throw new IllegalArgumentException("Push destination Tenant scope is required");
        }
    }

    private record StoredDestination(String ciphertext, String keyVersion, long version) {
        @Override public String toString() {
            return "StoredDestination[ciphertext=<redacted>, keyVersion=" + keyVersion + ", version=" + version + "]";
        }
    }
}
