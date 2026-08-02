package com.nexa.api.iam.infrastructure.notification;

import com.nexa.api.iam.application.port.out.SecurityNotificationOutboxPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcSecurityNotificationOutboxAdapter implements SecurityNotificationOutboxPort {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final byte[] key;

    public JdbcSecurityNotificationOutboxAdapter(JdbcTemplate jdbc,
            @Value("${nexa.security.notification-outbox-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) throw new IllegalStateException("Security notification outbox key is required");
        this.jdbc = jdbc;
        this.key = sha256(configuredKey);
    }

    @Override
    public void enqueuePasswordReset(String recipient, String surface, String token, Instant expiresAt) {
        enqueue("PASSWORD_RESET", recipient, surface, "token=" + token + "\nexpiresAt=" + expiresAt);
    }

    @Override
    public void enqueuePasswordChanged(String recipient, String surface) {
        enqueue("PASSWORD_CHANGED", recipient, surface, "changed=true");
    }

    private void enqueue(String type, String recipient, String surface, String payload) {
        Instant now = Instant.now();
        jdbc.update("insert into iam.security_notification_outbox (id,notification_type,recipient,surface,payload_ciphertext,status,attempt_count,next_attempt_at,created_at,version) values (?,?,?,?,?,'PENDING',0,?,?,0)",
                UUID.randomUUID(), type, recipient, surface, encrypt(payload), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
    }

    public String decrypt(String value) {
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(value);
            byte[] iv = java.util.Arrays.copyOfRange(encoded, 0, 12);
            byte[] ciphertext = java.util.Arrays.copyOfRange(encoded, 12, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Security notification payload cannot be decrypted", exception); }
    }

    private String encrypt(String value) {
        try {
            byte[] iv = new byte[12]; RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length); System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(result);
        } catch (Exception exception) { throw new IllegalStateException("Security notification payload cannot be encrypted", exception); }
    }

    private static byte[] sha256(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
}
