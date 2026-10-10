package com.nexa.api.edge.streaming.infrastructure;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.edge.streaming.ChangeEventAudience;
import com.nexa.api.edge.streaming.ChangeEventView;
import com.nexa.api.edge.streaming.ChangeFeedQueryPort;
import com.nexa.api.edge.streaming.ChangeFeedReadUseCase;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Preserves central-backed change-feed behavior for legacy workspaces. */
public final class CentralChangeFeedReadUseCase implements ChangeFeedReadUseCase {
    private final ChangeFeedQueryPort feed;
    private final CustomerAccountQuery accounts;

    public CentralChangeFeedReadUseCase(ChangeFeedQueryPort feed, CustomerAccountQuery accounts) {
        this.feed = Objects.requireNonNull(feed, "Change-feed query is required");
        this.accounts = Objects.requireNonNull(accounts, "Customer account query is required");
    }

    @Override
    public long minimumId(CurrentAccessContext context) {
        return feed.minimumId(tenant(context), workspace(context), clientAccount(context));
    }

    @Override
    public List<ChangeEventView> after(CurrentAccessContext context, Set<ChangeEventAudience> audiences,
                                       long lastEventId, int limit) {
        return feed.after(tenant(context), workspace(context), clientAccount(context), audiences, lastEventId, limit);
    }

    private String clientAccount(CurrentAccessContext context) {
        if (!context.hasRole(MembershipRole.BUYER)) return null;
        return accounts.findBuyerReference(tenant(context), workspace(context), context.membershipId().toString())
                .map(CustomerAccountReference::id).orElseThrow();
    }

    private static String tenant(CurrentAccessContext context) { return context.tenantId().toString(); }
    private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
}
