package com.nexa.api.iam.application.port.out;

import java.time.Instant;

/** Durable, bounded workspace-preview throttle; implementations must update atomically. */
public interface WorkspacePreviewThrottlePort {
    boolean allow(String workspaceSlug, String clientKey, Instant now);
}
