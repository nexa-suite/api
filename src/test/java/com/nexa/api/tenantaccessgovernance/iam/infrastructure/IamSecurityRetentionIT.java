package com.nexa.api.tenantaccessgovernance.iam.infrastructure;

import com.nexa.api.support.PostgresIntegrationSupport;
import com.nexa.api.tenantaccessgovernance.iam.infrastructure.security.IamSecurityRetentionJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "nexa.integration.enabled", matches = "true")
class IamSecurityRetentionIT extends PostgresIntegrationSupport {
    @Test
    void boundedRetentionDeletesExpiredConsumedAndRevokedContextTickets() {
        UUID userId = jdbc.queryForObject("select id from iam.user_account where normalized_email=?", UUID.class, OWNER_EMAIL);
        List<String> oldHashes = List.of(hash("retention-consumed"), hash("retention-revoked"), hash("retention-expired"));
        String activeHash = hash("retention-active");
        jdbc.update("insert into iam.access_context_selection_ticket (ticket_hash,user_id,surface,issued_at,expires_at,consumed_at) "
                        + "values (?,?, 'PLATFORM', current_timestamp - interval '45 days', current_timestamp - interval '44 days 23 hours 55 minutes', current_timestamp - interval '44 days')",
                oldHashes.get(0), userId);
        jdbc.update("insert into iam.access_context_selection_ticket (ticket_hash,user_id,surface,issued_at,expires_at,revoked_at) "
                        + "values (?,?, 'PLATFORM', current_timestamp - interval '55 days', current_timestamp - interval '54 days 23 hours 55 minutes', current_timestamp - interval '54 days')",
                oldHashes.get(1), userId);
        jdbc.update("insert into iam.access_context_selection_ticket (ticket_hash,user_id,surface,issued_at,expires_at,consumed_at) "
                        + "values (?,?, 'PLATFORM', current_timestamp - interval '65 days', current_timestamp - interval '64 days 23 hours 55 minutes', null)",
                oldHashes.get(2), userId);
        jdbc.update("insert into iam.access_context_selection_ticket (ticket_hash,user_id,surface,issued_at,expires_at,consumed_at) "
                        + "values (?,?, 'PLATFORM', current_timestamp, current_timestamp + interval '5 minutes', null)",
                activeHash, userId);

        IamSecurityRetentionJob job = new IamSecurityRetentionJob(jdbc, 2, 30);
        job.cleanupBounded();
        assertThat(jdbc.queryForObject("select count(*) from iam.access_context_selection_ticket where ticket_hash in (?,?,?)",
                Integer.class, oldHashes.get(0), oldHashes.get(1), oldHashes.get(2))).isEqualTo(1);
        job.cleanupBounded();

        assertThat(jdbc.queryForObject("select count(*) from iam.access_context_selection_ticket where ticket_hash in (?,?,?)",
                Integer.class, oldHashes.get(0), oldHashes.get(1), oldHashes.get(2))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from iam.access_context_selection_ticket where ticket_hash=?",
                Integer.class, activeHash)).isEqualTo(1);
        jdbc.update("delete from iam.access_context_selection_ticket where ticket_hash in (?,?,?,?)",
                oldHashes.get(0), oldHashes.get(1), oldHashes.get(2), activeHash);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
