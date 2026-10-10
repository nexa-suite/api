package com.nexa.api.payments.application;

import com.nexa.api.payments.application.model.BuyerWalletModels;
import com.nexa.api.payments.application.publicapi.BuyerWalletReadPort;
import com.nexa.api.payments.application.service.BuyerWalletReadService;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.EffectiveAuthorization;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.Membership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.MembershipStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.membership.VerifiedMembership;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.tenant.TenantStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.workspace.WorkspaceStatus;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.TenantId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.UserId;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuyerWalletReadServiceTests {

    @Test
    void readsOnlyCurrentBuyerIdentityAndProjectsBalancesAndPage() {
        CurrentAccessContext context = context(MembershipRole.BUYER, Surface.PORTAL);
        AtomicReference<java.util.UUID> requestedIdentity = new AtomicReference<>();
        BuyerWalletReadPort wallets = (access, identity, page, size) -> {
            requestedIdentity.set(identity);
            assertThat(access).isSameAs(context);
            assertThat(page).isEqualTo(2);
            assertThat(size).isEqualTo(10);
            return new BuyerWalletModels.Snapshot(BuyerWalletModels.WalletStatus.ACTIVE,
                    new BigDecimal("125.00"), new BigDecimal("25.00"),
                    List.of(new BuyerWalletModels.MovementView("CREDIT", new BigDecimal("125.00"),
                            Instant.parse("2026-10-09T10:00:00Z"))), 1);
        };

        BuyerWalletModels.WalletView result = new BuyerWalletReadService(wallets)
                .getCurrentBuyerWallet(context, 2, 10);

        assertThat(requestedIdentity).hasValue(context.userId().value());
        assertThat(result.postedBalance()).isEqualByComparingTo("125.00");
        assertThat(result.reservedBalance()).isEqualByComparingTo("25.00");
        assertThat(result.availableBalance()).isEqualByComparingTo("100.00");
        assertThat(result.movements().items()).hasSize(1);
        assertThat(result.capabilities().orderPaymentSupported()).isFalse();
    }

    @Test
    void doesNotReadWalletForNonBuyerMembership() {
        CurrentAccessContext context = contextWithPaymentRead(MembershipRole.SALES, Surface.PLATFORM);
        BuyerWalletReadPort wallets = (access, identity, page, size) -> {
            throw new AssertionError("Wallet query must not run for a non-Buyer membership");
        };

        assertThatThrownBy(() -> new BuyerWalletReadService(wallets).getCurrentBuyerWallet(context, 0, 25))
                .isInstanceOf(AccessPolicyViolation.class);
    }

    @Test
    void missingWalletReturnsExplicitUninitializedStateWithoutInventedZeroBalances() {
        CurrentAccessContext context = context(MembershipRole.BUYER, Surface.PORTAL);
        BuyerWalletReadPort wallets = (access, identity, page, size) -> new BuyerWalletModels.Snapshot(
                BuyerWalletModels.WalletStatus.NOT_INITIALIZED, null, null, List.of(), 0);

        BuyerWalletModels.WalletView result = new BuyerWalletReadService(wallets)
                .getCurrentBuyerWallet(context, 0, 25);

        assertThat(result.status()).isEqualTo(BuyerWalletModels.WalletStatus.NOT_INITIALIZED);
        assertThat(result.postedBalance()).isNull();
        assertThat(result.reservedBalance()).isNull();
        assertThat(result.availableBalance()).isNull();
        assertThat(result.movements().items()).isEmpty();
        assertThat(result.capabilities().orderPaymentSupported()).isFalse();
    }

    @Test
    void exposesOrderPaymentSupportOnlyWhenTenantReadPortReportsIt() {
        CurrentAccessContext context = context(MembershipRole.BUYER, Surface.PORTAL);
        BuyerWalletReadPort wallets = new BuyerWalletReadPort() {
            @Override
            public BuyerWalletModels.Snapshot read(CurrentAccessContext access, java.util.UUID identity,
                    int page, int size) {
                return new BuyerWalletModels.Snapshot(BuyerWalletModels.WalletStatus.ACTIVE,
                        BigDecimal.ZERO, BigDecimal.ZERO, List.of(), 0);
            }

            @Override
            public boolean orderPaymentSupported(CurrentAccessContext access) {
                assertThat(access).isSameAs(context);
                return true;
            }
        };

        BuyerWalletModels.WalletView result = new BuyerWalletReadService(wallets)
                .getCurrentBuyerWallet(context, 0, 25);

        assertThat(result.capabilities().orderPaymentSupported()).isTrue();
    }

    private static CurrentAccessContext context(MembershipRole role, Surface surface) {
        Membership membership = new Membership(MembershipId.random(), UserId.random(), TenantId.random(),
                WorkspaceId.random(), Set.of(role), MembershipStatus.ACTIVE);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE), surface);
    }

    private static CurrentAccessContext contextWithPaymentRead(MembershipRole role, Surface surface) {
        Membership membership = new Membership(MembershipId.random(), UserId.random(), TenantId.random(),
                WorkspaceId.random(), Set.of(role), Set.of("sales.viewer"), Set.of("sales.viewer"),
                MembershipStatus.ACTIVE, 0);
        EffectiveAuthorization authorization = new EffectiveAuthorization(Set.of("sales.viewer"),
                Set.of("sales.viewer"), Set.of("payment.read"), 0);
        return CurrentAccessContext.from(new VerifiedMembership(membership, TenantStatus.ACTIVE,
                WorkspaceStatus.ACTIVE, authorization), surface);
    }
}
