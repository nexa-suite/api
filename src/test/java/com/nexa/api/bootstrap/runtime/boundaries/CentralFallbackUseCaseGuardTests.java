package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
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
import com.nexa.api.salescommitment.application.purchaserequest.port.FieldPurchaseRequestSubmissionUseCase;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CentralFallbackUseCaseGuardTests {
    @Test
    void tenantCannotUseCentralFallback() {
        CurrentAccessContext context = context();
        AtomicBoolean invoked = new AtomicBoolean();
        FieldPurchaseRequestSubmissionUseCase central = (access, command, key) -> {
            invoked.set(true);
            return null;
        };
        FieldPurchaseRequestSubmissionUseCase guarded = CentralFallbackUseCaseGuard.protect(
                FieldPurchaseRequestSubmissionUseCase.class, central,
                new CentralTenantBusinessDataAccessGuard());

        assertThatThrownBy(() -> guarded.submit(context, null, "retry-key"))
                .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
        assertThat(invoked).isFalse();
    }

    @Test
    void missingVerifiedContextFailsClosed() {
        FieldPurchaseRequestSubmissionUseCase central = (context, command, key) -> null;
        FieldPurchaseRequestSubmissionUseCase guarded = CentralFallbackUseCaseGuard.protect(
                FieldPurchaseRequestSubmissionUseCase.class, central,
                new CentralTenantBusinessDataAccessGuard());

        assertThatThrownBy(() -> guarded.submit(null, null, "retry-key"))
                .isInstanceOf(TenantBusinessDatabaseUnavailableException.class);
    }

    private static CurrentAccessContext context() {
        UUID tenantId = UUID.randomUUID();
        Membership membership = new Membership(new MembershipId(UUID.randomUUID()), new UserId(UUID.randomUUID()),
                new TenantId(tenantId), new WorkspaceId(UUID.randomUUID()), Set.of(MembershipRole.BUYER),
                MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), Surface.PORTAL);
    }
}
