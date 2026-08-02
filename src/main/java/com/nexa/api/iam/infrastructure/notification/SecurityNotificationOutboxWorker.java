package com.nexa.api.iam.infrastructure.notification;

import com.nexa.api.iam.application.port.out.PasswordResetDeliveryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!test")
public final class SecurityNotificationOutboxWorker {
    private final JdbcTemplate jdbc;
    private final JdbcSecurityNotificationOutboxAdapter outbox;
    private final PasswordResetDeliveryPort delivery;
    private final int maxAttempts;

    public SecurityNotificationOutboxWorker(JdbcTemplate jdbc, JdbcSecurityNotificationOutboxAdapter outbox,
            PasswordResetDeliveryPort delivery,
            @org.springframework.beans.factory.annotation.Value("${nexa.security.notification-outbox.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc; this.outbox = outbox; this.delivery = delivery; this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${nexa.security.notification-outbox.poll-delay:PT5S}")
    public void deliverPending() {
		jdbc.update("update iam.security_notification_outbox set status='PENDING',next_attempt_at=current_timestamp,version=version+1 where status='PROCESSING' and created_at < current_timestamp - interval '10 minutes'");
        List<Row> rows = jdbc.query("select id,notification_type,recipient,surface,payload_ciphertext,attempt_count from iam.security_notification_outbox where status='PENDING' and next_attempt_at<=current_timestamp order by created_at asc limit 20",
                (rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("notification_type"), rs.getString("recipient"), rs.getString("surface"), rs.getString("payload_ciphertext"), rs.getInt("attempt_count")));
        for (Row row : rows) {
            if (jdbc.update("update iam.security_notification_outbox set status='PROCESSING',version=version+1 where id=? and status='PENDING'", row.id()) != 1) continue;
            try {
                String payload = outbox.decrypt(row.payload());
                if ("PASSWORD_RESET".equals(row.type())) {
                    String token = payload.substring(payload.indexOf("token=") + 6, payload.indexOf('\n'));
                    String expires = payload.substring(payload.indexOf("expiresAt=") + 10);
                    delivery.sendReset(row.recipient(), row.surface(), token, Instant.parse(expires));
                } else if ("PASSWORD_CHANGED".equals(row.type())) {
                    delivery.sendPasswordChanged(row.recipient(), row.surface());
                } else throw new IllegalStateException("Unknown security notification type");
                jdbc.update("update iam.security_notification_outbox set status='SENT',payload_ciphertext='',sent_at=current_timestamp,attempt_count=attempt_count+1,version=version+1 where id=? and status='PROCESSING'", row.id());
            } catch (RuntimeException exception) {
                int attempts = row.attempts() + 1;
                String status = attempts >= maxAttempts ? "DEAD_LETTER" : "PENDING";
				jdbc.update("update iam.security_notification_outbox set status=?,payload_ciphertext=case when ?='DEAD_LETTER' then '' else payload_ciphertext end,attempt_count=?,next_attempt_at=current_timestamp + (? * interval '30 seconds'),last_error_code=?,version=version+1 where id=? and status='PROCESSING'",
						status, status, attempts, attempts, exception.getClass().getSimpleName(), row.id());
            }
        }
    }

    private record Row(UUID id, String type, String recipient, String surface, String payload, int attempts) {}
}
