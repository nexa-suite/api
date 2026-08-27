package com.nexa.api.catalogcommercialpolicy.infrastructure.persistence;

import com.nexa.api.catalogcommercialpolicy.application.exception.CatalogConflictException;
import com.nexa.api.catalogcommercialpolicy.application.model.CatalogScope;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Transaction-local idempotency reservation for catalog commands. */
final class CatalogCommandIdempotencySupport {
    private final JdbcTemplate jdbc;

    CatalogCommandIdempotencySupport(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    UUID reserve(CatalogScope scope, String operation, String key, String requestHash, UUID candidateId) {
        if (key == null || key.isBlank()) return candidateId;
        int inserted = jdbc.update("insert into catalog_management.command_idempotency (tenant_id,workspace_id,operation,idempotency_key,request_hash,resource_type,resource_id,created_at) values (?,?,?,?,?,'catalog',?,?) "
                        + "on conflict (tenant_id,workspace_id,operation,idempotency_key) do nothing",
                scope.tenantId(), scope.workspaceId(), operation, key, requestHash, candidateId, Timestamp.from(Instant.now()));
        if (inserted == 1) return candidateId;
        Record existing = jdbc.query("select request_hash,resource_id from catalog_management.command_idempotency where tenant_id=? and workspace_id=? and operation=? and idempotency_key=?",
                (rs, row) -> new Record(rs.getString(1), rs.getObject(2, UUID.class)), scope.tenantId(), scope.workspaceId(), operation, key)
                .stream().findFirst().orElseThrow(() -> new CatalogConflictException("IDEMPOTENCY_RESERVATION_NOT_FOUND"));
        if (!existing.hash().equals(requestHash)) throw new CatalogConflictException("IDEMPOTENCY_PAYLOAD_CONFLICT");
        return existing.resourceId();
    }

    static String hash(Object... values) {
        StringBuilder input = new StringBuilder();
        for (Object value : values) input.append(value == null ? "<null>" : value).append('\u001f');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record Record(String hash, UUID resourceId) { }
}
