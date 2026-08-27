package com.nexa.api.tenantaccessgovernance.iam.domain.model.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordResetRequestTests {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final PasswordResetTokenHash HASH = new PasswordResetTokenHash("a".repeat(64));
    private PasswordResetRequest pending() { return PasswordResetRequest.pending(new PasswordResetRequestId(UUID.randomUUID()), HASH, CREATED, new PasswordResetExpiry(CREATED.plusSeconds(60))); }

    @Test void consumesExactlyOnce() { var request = pending(); request.consume(CREATED.plusSeconds(1)); assertThat(request.status()).isEqualTo(PasswordResetStatus.CONSUMED); assertThatThrownBy(() -> request.consume(CREATED.plusSeconds(2))).isInstanceOf(IllegalStateException.class); }
    @Test void expiresAndCannotBeConsumed() { var request = pending(); request.expire(CREATED.plusSeconds(60)); assertThat(request.status()).isEqualTo(PasswordResetStatus.EXPIRED); assertThatThrownBy(() -> request.consume(CREATED.plusSeconds(61))).isInstanceOf(IllegalStateException.class); }
    @Test void revokesOnlyPendingRequest() { var request = pending(); request.revoke(); assertThat(request.status()).isEqualTo(PasswordResetStatus.REVOKED); assertThatThrownBy(() -> request.consume(CREATED.plusSeconds(1))).isInstanceOf(IllegalStateException.class); }
    @Test void rejectsInvalidCreationAndAttempts() { assertThatThrownBy(() -> PasswordResetRequest.pending(new PasswordResetRequestId(UUID.randomUUID()), HASH, CREATED, new PasswordResetExpiry(CREATED))).isInstanceOf(IllegalArgumentException.class); assertThatThrownBy(() -> PasswordResetRequest.restore(new PasswordResetRequestId(UUID.randomUUID()), HASH, CREATED, new PasswordResetExpiry(CREATED.plusSeconds(1)), PasswordResetStatus.PENDING, 11)).isInstanceOf(IllegalArgumentException.class); }
}
