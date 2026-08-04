package com.nexa.api.iam.infrastructure.persistence;

import com.nexa.api.iam.application.port.out.WorkspacePreviewThrottlePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcWorkspacePreviewThrottleAdapter implements WorkspacePreviewThrottlePort {
    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private final JdbcTemplate jdbc;

    public JdbcWorkspacePreviewThrottleAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean allow(String workspaceSlug, String clientKey, Instant now) {
        String workspace = workspaceSlug == null ? "unknown" : workspaceSlug;
        String clientHash = sha256(clientKey == null ? "unknown" : clientKey);
        String bucket = sha256(workspace + ":" + clientHash);
        Integer count = jdbc.queryForObject("insert into iam.workspace_preview_throttle_bucket (bucket_key_hash,workspace_slug,client_key_hash,window_started_at,request_count,updated_at) values (?,?,?,?,1,?) on conflict (bucket_key_hash) do update set request_count=case when iam.workspace_preview_throttle_bucket.window_started_at <= current_timestamp - interval '1 minute' then 1 else iam.workspace_preview_throttle_bucket.request_count+1 end,window_started_at=case when iam.workspace_preview_throttle_bucket.window_started_at <= current_timestamp - interval '1 minute' then current_timestamp else iam.workspace_preview_throttle_bucket.window_started_at end,updated_at=current_timestamp returning request_count", Integer.class, bucket, workspace, clientHash, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));
        return count != null && count <= MAX_REQUESTS_PER_MINUTE;
    }

    @Scheduled(fixedDelayString = "${nexa.iam.workspace-preview-throttle-cleanup-ms:3600000}")
    public void removeExpiredBuckets() {
        jdbc.update("delete from iam.workspace_preview_throttle_bucket where ctid in (select ctid from iam.workspace_preview_throttle_bucket where updated_at < current_timestamp - interval '2 hours' order by updated_at asc limit 500)");
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Workspace preview throttle hash failed", exception); }
    }
}
