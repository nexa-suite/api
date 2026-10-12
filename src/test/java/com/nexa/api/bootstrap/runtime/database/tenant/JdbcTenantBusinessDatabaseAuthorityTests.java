package com.nexa.api.bootstrap.runtime.database.tenant;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcTenantBusinessDatabaseAuthorityTests {

    @Test
    void revalidatesCentralMembershipBeforeReadingBindingOrOpeningTenantPool() {
        CurrentAccessContext presented = context();
        CurrentAccessContext changed = context();
        AtomicInteger bindingReads = new AtomicInteger();
        AtomicInteger tenantPoolCreations = new AtomicInteger();
        JdbcTenantBusinessDatabaseAuthority authority = new JdbcTenantBusinessDatabaseAuthority(
                request -> changed,
                tenantId -> {
                    bindingReads.incrementAndGet();
                    return Optional.empty();
                });
        TenantBusinessDatabaseRouter router = new TenantBusinessDatabaseRouter(authority, binding -> {
            tenantPoolCreations.incrementAndGet();
            throw new AssertionError("Tenant pool must not open for stale central access");
        }, 1);

        try {
            assertThatThrownBy(() -> router.inTransaction(presented, ignored -> null))
                    .isInstanceOf(AccessDeniedException.class);
            assertThat(bindingReads).hasValue(0);
            assertThat(tenantPoolCreations).hasValue(0);
        } finally {
            router.close();
        }
    }

    private static CurrentAccessContext context() {
        Membership membership = new Membership(MembershipId.random(), UserId.random(), TenantId.random(),
                WorkspaceId.random(), Set.of(MembershipRole.BUYER), MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), Surface.PORTAL);
    }
}
