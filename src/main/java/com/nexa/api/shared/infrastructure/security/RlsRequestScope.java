package com.nexa.api.shared.infrastructure.security;

import java.util.UUID;

/** Request-local database scope consumed by the PostgreSQL connection wrapper. */
public final class RlsRequestScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private RlsRequestScope() { }

    public static void set(UUID tenantId, UUID workspaceId) {
        CURRENT.set(new Scope(tenantId, workspaceId));
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public record Scope(UUID tenantId, UUID workspaceId) { }
}
