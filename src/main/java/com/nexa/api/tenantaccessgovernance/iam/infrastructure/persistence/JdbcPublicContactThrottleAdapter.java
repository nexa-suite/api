package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PublicContactThrottlePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcPublicContactThrottleAdapter implements PublicContactThrottlePort {
    private final JdbcTemplate jdbc;
    private final byte[] key;
    private final Clock clock;

    public JdbcPublicContactThrottleAdapter(JdbcTemplate jdbc,
            @Value("${nexa.security.public-contact.throttle-key:${nexa.security.reset.throttle-key:}}") String configuredKey,
            Clock clock) {
        if (configuredKey == null || configuredKey.isBlank()) throw new IllegalStateException("Public contact throttle key is required");
        this.jdbc = jdbc;
        this.key = configuredKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override
    public long recordAttempt(String normalizedEmail, String clientAddress) {
        Instant now = clock.instant();
        return Math.max(bucket("EMAIL", normalizedEmail, now), bucket("IP", clientAddress, now));
    }

    private long bucket(String dimension, String value, Instant now) {
        String keyHash = hmac(value == null || value.isBlank() ? "unknown" : value.strip());
        Long count = jdbc.queryForObject("insert into iam.public_contact_throttle_bucket "
                        + "(throttle_dimension,key_hash,window_started_at,request_count,updated_at) values (?,?,?,1,?) "
                        + "on conflict (throttle_dimension,key_hash) do update set "
                        + "request_count=case when iam.public_contact_throttle_bucket.window_started_at <= current_timestamp - interval '1 hour' then 1 else iam.public_contact_throttle_bucket.request_count+1 end, "
                        + "window_started_at=case when iam.public_contact_throttle_bucket.window_started_at <= current_timestamp - interval '1 hour' then current_timestamp else iam.public_contact_throttle_bucket.window_started_at end, "
                        + "updated_at=current_timestamp returning request_count", Long.class,
                dimension, keyHash, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return count == null ? 1 : count;
    }

    @Scheduled(fixedDelayString = "${nexa.iam.public-contact-throttle-cleanup-ms:3600000}")
    public void removeExpiredBuckets() {
        jdbc.update("delete from iam.public_contact_throttle_bucket where ctid in "
                + "(select ctid from iam.public_contact_throttle_bucket where updated_at < current_timestamp - interval '2 hours' "
                + "order by updated_at asc limit 500)");
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Public contact throttle key is invalid", exception);
        }
    }
}
