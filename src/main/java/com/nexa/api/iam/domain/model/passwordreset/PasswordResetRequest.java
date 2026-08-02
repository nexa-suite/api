package com.nexa.api.iam.domain.model.passwordreset;

import java.time.Instant;
import java.util.Objects;

/** Domain lifecycle for one opaque, single-use reset request. */
public final class PasswordResetRequest {
    private final PasswordResetRequestId id;
    private final PasswordResetTokenHash tokenHash;
    private final Instant createdAt;
    private final PasswordResetExpiry expiry;
    private PasswordResetStatus status;
    private int attempts;

    private PasswordResetRequest(PasswordResetRequestId id, PasswordResetTokenHash tokenHash, Instant createdAt,
            PasswordResetExpiry expiry, PasswordResetStatus status, int attempts) {
        this.id = Objects.requireNonNull(id); this.tokenHash = Objects.requireNonNull(tokenHash);
        this.createdAt = Objects.requireNonNull(createdAt); this.expiry = Objects.requireNonNull(expiry);
        this.status = Objects.requireNonNull(status);
        if (attempts < 0 || attempts > 10) throw new IllegalArgumentException("Reset attempts out of range");
        this.attempts = attempts;
    }

    public static PasswordResetRequest pending(PasswordResetRequestId id, PasswordResetTokenHash tokenHash,
            Instant createdAt, PasswordResetExpiry expiry) {
        if (!expiry.value().isAfter(createdAt)) throw new IllegalArgumentException("Reset expiry must be in the future");
        return new PasswordResetRequest(id, tokenHash, createdAt, expiry, PasswordResetStatus.PENDING, 0);
    }

    public static PasswordResetRequest restore(PasswordResetRequestId id, PasswordResetTokenHash tokenHash,
            Instant createdAt, PasswordResetExpiry expiry, PasswordResetStatus status, int attempts) {
        return new PasswordResetRequest(id, tokenHash, createdAt, expiry, status, attempts);
    }

    public boolean isUsableAt(Instant now) { return status == PasswordResetStatus.PENDING && expiry.value().isAfter(now); }
    public void expire(Instant now) { if (status == PasswordResetStatus.PENDING && expiry.hasExpiredAt(now)) status = PasswordResetStatus.EXPIRED; }
    public void consume(Instant now) { if (!isUsableAt(now)) throw new IllegalStateException("Password reset request is not usable"); status = PasswordResetStatus.CONSUMED; attempts++; }
    public void revoke() { if (status == PasswordResetStatus.PENDING) status = PasswordResetStatus.REVOKED; }
    public PasswordResetRequestId id() { return id; }
    public PasswordResetTokenHash tokenHash() { return tokenHash; }
    public Instant createdAt() { return createdAt; }
    public PasswordResetExpiry expiry() { return expiry; }
    public PasswordResetStatus status() { return status; }
    public int attempts() { return attempts; }
}
