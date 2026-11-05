package com.nexa.api.tenantaccessgovernance.iam.infrastructure.security;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PasswordResetThrottlePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcPasswordResetThrottleAdapter implements PasswordResetThrottlePort {
    private final JdbcTemplate jdbc; private final byte[] key; private final Clock clock;
    private final TransactionTemplate attemptsTransaction;
    public JdbcPasswordResetThrottleAdapter(JdbcTemplate jdbc, @Value("${nexa.security.reset.throttle-key:}") String configuredKey,
            Clock clock, PlatformTransactionManager transactionManager) {
        if (configuredKey == null || configuredKey.isBlank()) throw new IllegalStateException("Password reset throttle key is required");
        this.jdbc = jdbc; this.key = configuredKey.getBytes(StandardCharsets.UTF_8); this.clock = clock;
        this.attemptsTransaction = new TransactionTemplate(transactionManager);
        this.attemptsTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    @Override public long recordAttempt(String normalizedIdentifier, String clientAddress) {
        Instant now = clock.instant();
        return Math.max(bucket("EMAIL", hmac(normalizedIdentifier), now),
                bucket("IP", hmac(address(clientAddress)), now));
    }
    @Override public long recordConsumptionAttempt(String clientAddress) {
        Instant now = clock.instant();
        return Objects.requireNonNull(attemptsTransaction.execute(status ->
                bucket("IP", hmac("CONSUME:" + address(clientAddress)), now)));
    }
    private static String address(String clientAddress) {
        return clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress.trim();
    }
    private long bucket(String dimension, String hash, Instant now) {
        Long count = jdbc.queryForObject("insert into iam.password_reset_throttle_bucket (throttle_dimension,key_hash,window_started_at,request_count,updated_at) values (?,?,?,1,?) on conflict (throttle_dimension,key_hash) do update set request_count=case when iam.password_reset_throttle_bucket.window_started_at <= current_timestamp - interval '10 minutes' then 1 else iam.password_reset_throttle_bucket.request_count+1 end,window_started_at=case when iam.password_reset_throttle_bucket.window_started_at <= current_timestamp - interval '10 minutes' then current_timestamp else iam.password_reset_throttle_bucket.window_started_at end,updated_at=current_timestamp returning request_count", Long.class, dimension, hash, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return count == null ? 1 : count;
    }
    private String hmac(String value) { try { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception exception) { throw new IllegalStateException("Password reset throttle key is invalid", exception); } }
}
