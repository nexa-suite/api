package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.edge.streaming.ChangeEventAudience;
import com.nexa.api.edge.streaming.ChangeEventView;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import com.nexa.api.edge.streaming.tenantdatabase.TenantChangeFeedQueryFactory;
import com.nexa.api.shared.application.error.TechnicalFailureException;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executes each authenticated feed read in a short, exact Tenant transaction. */
public final class TenantBoundChangeFeedReadUseCase implements ChangeFeedReadUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerAccountQueryFactory accounts;
    private final TenantChangeFeedQueryFactory feed;

    public TenantBoundChangeFeedReadUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerAccountQueryFactory accounts, TenantChangeFeedQueryFactory feed) {
        this.router = Objects.requireNonNull(router, "Tenant database router is required");
        this.accounts = Objects.requireNonNull(accounts, "Tenant customer account query factory is required");
        this.feed = Objects.requireNonNull(feed, "Tenant change-feed query factory is required");
    }

    @Override
    public long minimumId(CurrentAccessContext context) {
        return execute(context, jdbc -> feed.bindTo(jdbc).minimumId(tenant(context), workspace(context), clientAccount(jdbc, context)));
    }

    @Override
    public List<ChangeEventView> after(CurrentAccessContext context, Set<ChangeEventAudience> audiences,
                                       long lastEventId, int limit) {
        return execute(context, jdbc -> feed.bindTo(jdbc).after(tenant(context), workspace(context),
                clientAccount(jdbc, context), audiences, lastEventId, limit));
    }

    private <T> T execute(CurrentAccessContext context,
            java.util.function.Function<JdbcTemplate, T> work) {
        Objects.requireNonNull(context, "Revalidated Tenant access context is required");
        try {
            return router.inTransaction(context, work::apply);
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new TechnicalFailureException(TechnicalFailureException.Kind.TECHNICAL_CAPABILITY_UNAVAILABLE,
                    "Tenant change-feed capability is unavailable", unavailable);
        }
    }

    private String clientAccount(JdbcTemplate jdbc, CurrentAccessContext context) {
        if (!context.hasRole(MembershipRole.BUYER)) return null;
        return accounts.bindTo(jdbc)
                .findBuyerReference(tenant(context), workspace(context), context.membershipId().toString())
                .map(CustomerAccountReference::id).orElseThrow();
    }

    private static String tenant(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
}
