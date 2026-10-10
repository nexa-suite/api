package com.nexa.api.edge.streaming;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.Set;

/** Authenticated read boundary for replayable change-feed queries. */
public interface ChangeFeedReadUseCase {
    long minimumId(CurrentAccessContext context);

    List<ChangeEventView> after(CurrentAccessContext context, Set<ChangeEventAudience> audiences,
                                long lastEventId, int limit);
}
