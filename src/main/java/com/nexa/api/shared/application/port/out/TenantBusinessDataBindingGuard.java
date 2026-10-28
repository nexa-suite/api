package com.nexa.api.shared.application.port.out;

import java.util.UUID;

/**
 * Technical guard for background work that may touch legacy central business data.
 * Unknown binding state must prevent central processing; the lock check is used in
 * the same central transaction as a legacy mutation to prevent a provisioning race.
 */
public interface TenantBusinessDataBindingGuard {
    /** Unknown guards conservatively treat the Tenant as already routed. */
    default boolean hasAnyBinding(UUID tenantId) {
        return true;
    }

    /** Unknown guards cannot prove route absence while holding the registry lock. */
    default boolean lockAndCheckNoBinding(UUID tenantId) {
        return false;
    }
}
