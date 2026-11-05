package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.payments.application.exception.BuyerWalletRefundUnavailableException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TenantBoundSalesOrderRefundAvailabilityTests {
    @Test
    void staleTenantSchemaFailsRefundClosedBeforeCompositionOrSql() {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
        TenantSalesCommitmentCompositionProvider compositions = mock(TenantSalesCommitmentCompositionProvider.class);
        TenantBoundSalesOrderUseCase useCase = useCase(router, compositions);
        when(router.inTransaction(eq(context), any()))
                .thenThrow(new TenantBusinessDatabaseSchemaManifestMismatchException());

        assertThatThrownBy(() -> useCase.refundBuyerWallet(context, "order-id", BigDecimal.ONE,
                "operator reason", "refund-key"))
                .isInstanceOf(BuyerWalletRefundUnavailableException.class);

        verifyNoInteractions(compositions);
    }

    @Test
    void unreadyTenantBindingFailsCandidateReadClosedBeforeCompositionOrSql() {
        CurrentAccessContext context = mock(CurrentAccessContext.class);
        TenantBusinessDatabaseRouter router = mock(TenantBusinessDatabaseRouter.class);
        TenantSalesCommitmentCompositionProvider compositions = mock(TenantSalesCommitmentCompositionProvider.class);
        TenantBoundSalesOrderUseCase useCase = useCase(router, compositions);
        when(router.inTransaction(eq(context), any()))
                .thenThrow(new TenantBusinessDatabaseUnavailableException("Tenant database is not READY"));

        assertThatThrownBy(() -> useCase.walletRefundCandidates(context, 0, 25))
                .isInstanceOf(BuyerWalletRefundUnavailableException.class);

        verifyNoInteractions(compositions);
    }

    private static TenantBoundSalesOrderUseCase useCase(TenantBusinessDatabaseRouter router,
            TenantSalesCommitmentCompositionProvider compositions) {
        return new TenantBoundSalesOrderUseCase(router,
                mock(TenantBusinessDatabasePurchaseRequestExpiryPolicyResolver.class), compositions);
    }
}
