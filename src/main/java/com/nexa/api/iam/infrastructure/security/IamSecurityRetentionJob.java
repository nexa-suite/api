package com.nexa.api.iam.infrastructure.security;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public final class IamSecurityRetentionJob {
    private final JdbcTemplate jdbc;
    private final int batchSize;
    private final int retentionDays;

    public IamSecurityRetentionJob(JdbcTemplate jdbc,
            @org.springframework.beans.factory.annotation.Value("${nexa.security.retention.batch-size:200}") int batchSize,
            @org.springframework.beans.factory.annotation.Value("${nexa.security.retention.reset-days:30}") int retentionDays) {
        this.jdbc = jdbc; this.batchSize = Math.max(1, batchSize); this.retentionDays = Math.max(1, retentionDays);
    }

    @Scheduled(fixedDelayString = "${nexa.security.retention.poll-delay:PT15M}")
    public void cleanupBounded() {
        jdbc.update("with expired as (select id from iam.password_reset_request where status='PENDING' and expires_at<=current_timestamp order by expires_at asc limit ?) update iam.password_reset_request r set status='EXPIRED' from expired where r.id=expired.id", batchSize);
        jdbc.update("delete from iam.password_reset_throttle_bucket where ctid in (select ctid from iam.password_reset_throttle_bucket where updated_at < current_timestamp - interval '1 day' order by updated_at asc limit ?)", batchSize);
        jdbc.update("update iam.security_notification_outbox set payload_ciphertext='',version=version+1 where id in (select id from iam.security_notification_outbox where status in ('SENT','DEAD_LETTER') and created_at < current_timestamp - (? * interval '1 day') order by created_at asc limit ?)", retentionDays, batchSize);
        jdbc.update("delete from iam.password_reset_request where id in (select id from iam.password_reset_request where status in ('CONSUMED','EXPIRED','REVOKED') and created_at < current_timestamp - (? * interval '1 day') order by created_at asc limit ?)", retentionDays, batchSize);
    }
}
