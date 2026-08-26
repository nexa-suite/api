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
    private final String workerId = "nexa-outbox-" + UUID.randomUUID();

    public SecurityNotificationOutboxWorker(JdbcTemplate jdbc, JdbcSecurityNotificationOutboxAdapter outbox,
            PasswordResetDeliveryPort delivery,
            @org.springframework.beans.factory.annotation.Value("${nexa.security.notification-outbox.max-attempts:8}") int maxAttempts) {
        this.jdbc = jdbc; this.outbox = outbox; this.delivery = delivery; this.maxAttempts = Math.max(1, maxAttempts);
    }

    @Scheduled(fixedDelayString = "${nexa.security.notification-outbox.poll-delay:PT5S}")
    public void deliverPending() {
        jdbc.update("update iam.security_notification_outbox set status='PENDING',processing_started_at=null,lease_until=null,claim_token=null,locked_by=null,next_attempt_at=current_timestamp,version=version+1 where status='PROCESSING' and (lease_until is null or lease_until <= current_timestamp)");
        UUID claimToken = UUID.randomUUID();
        List<Row> rows = jdbc.query("""
                with claimed as (
                    select id from iam.security_notification_outbox
                    where status='PENDING' and next_attempt_at<=current_timestamp
                    order by created_at asc, id asc
                    for update skip locked limit 20
                )
                update iam.security_notification_outbox o
                set status='PROCESSING', processing_started_at=current_timestamp, lease_until=current_timestamp + interval '10 minutes', claim_token=?, locked_by=?, version=version+1
                from claimed c where o.id=c.id
                    returning o.id,o.notification_type,o.recipient,o.surface,o.payload_ciphertext,o.payload_key_version,o.delivery_key,o.attempt_count
                """, (rs, row) -> new Row(rs.getObject("id", UUID.class), rs.getString("notification_type"), rs.getString("recipient"), rs.getString("surface"), rs.getString("payload_ciphertext"), rs.getString("payload_key_version"), rs.getString("delivery_key"), rs.getInt("attempt_count"), claimToken), claimToken, workerId);
        for (Row row : rows) {
            try {
                String payload = outbox.decrypt(row.payload(), row.keyVersion());
                if ("PASSWORD_RESET".equals(row.type())) {
                    String token = payload.substring(payload.indexOf("token=") + 6, payload.indexOf('\n'));
                    String expires = payload.substring(payload.indexOf("expiresAt=") + 10);
                    delivery.sendReset(row.recipient(), row.surface(), token, Instant.parse(expires), row.deliveryKey());
                } else if ("PASSWORD_CHANGED".equals(row.type())) {
                    delivery.sendPasswordChanged(row.recipient(), row.surface(), row.deliveryKey());
                } else if ("ORGANIZATION_INVITATION".equals(row.type())) {
                    String displayName = payload.substring(payload.indexOf("displayName=") + 12, payload.indexOf('\n'));
                    String token = payload.substring(payload.indexOf("token=") + 6, payload.indexOf('\n', payload.indexOf("token=")));
                    String expires = payload.substring(payload.indexOf("expiresAt=") + 10);
                    delivery.sendInvitation(row.recipient(), displayName, token, Instant.parse(expires), row.deliveryKey());
                } else throw new IllegalStateException("Unknown security notification type");
					jdbc.update("update iam.security_notification_outbox set status='SENT',payload_ciphertext='',processing_started_at=null,lease_until=null,claim_token=null,locked_by=null,sent_at=current_timestamp,attempt_count=attempt_count+1,version=version+1 where id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp", row.id(), row.claimToken());
            } catch (RuntimeException exception) {
                int attempts = row.attempts() + 1;
                String status = attempts >= maxAttempts ? "DEAD_LETTER" : "PENDING";
					jdbc.update("update iam.security_notification_outbox set status=?,payload_ciphertext=case when ?='DEAD_LETTER' then '' else payload_ciphertext end,processing_started_at=null,lease_until=null,claim_token=null,locked_by=null,attempt_count=?,next_attempt_at=current_timestamp + (? * interval '30 seconds'),last_error_code=?,version=version+1 where id=? and status='PROCESSING' and claim_token=? and lease_until > current_timestamp",
						status, status, attempts, attempts, exception.getClass().getSimpleName(), row.id(), row.claimToken());
            }
        }
    }

    private record Row(UUID id, String type, String recipient, String surface, String payload, String keyVersion,
                       String deliveryKey, int attempts, UUID claimToken) {}
}
