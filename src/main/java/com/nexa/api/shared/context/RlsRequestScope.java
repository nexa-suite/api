package com.nexa.api.shared.context;

import java.util.UUID;

/** Request-local database scope consumed by the PostgreSQL connection wrapper. */
public final class RlsRequestScope {
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CROSS_SCOPE_WORKSPACE_SCAN = new ThreadLocal<>();

    private RlsRequestScope() { }

    public static void set(UUID tenantId, UUID workspaceId) {
        CURRENT.set(new Scope(tenantId, workspaceId));
    }

    public static Scope current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        CROSS_SCOPE_WORKSPACE_SCAN.remove();
    }

    /** Enables the narrowly bounded workspace enumeration used by startup and maintenance workers. */
    public static void enableCrossScopeWorkspaceScan() {
        CROSS_SCOPE_WORKSPACE_SCAN.set(Boolean.TRUE);
    }

    public static boolean crossScopeWorkspaceScanEnabled() {
        return Boolean.TRUE.equals(CROSS_SCOPE_WORKSPACE_SCAN.get());
    }

    public static void clearCrossScopeWorkspaceScan() {
        CROSS_SCOPE_WORKSPACE_SCAN.remove();
    }

    public record Scope(UUID tenantId, UUID workspaceId) { }
}
