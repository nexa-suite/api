package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

import java.util.Optional;
import java.util.UUID;

/** Pure V1 Driver claim/review transitions. This lifecycle cannot resolve a source fact. */
public final class OperationalExceptionLifecycle {
    private OperationalExceptionLifecycle() { }

    public record Transition(OperationalExceptionStatus status, UUID responsibleMembershipId,
                             UUID actorMembershipId, String reasonCode) { }

    public static Optional<Transition> claim(OperationalExceptionStatus currentStatus,
                                             UUID currentResponsibleMembershipId,
                                             UUID actorMembershipId) {
        if (currentStatus != OperationalExceptionStatus.OPEN || currentResponsibleMembershipId != null
                || actorMembershipId == null) return Optional.empty();
        return Optional.of(new Transition(OperationalExceptionStatus.CLAIMED, actorMembershipId,
                actorMembershipId, "DRIVER_CLAIMED"));
    }

    public static Optional<Transition> review(OperationalExceptionStatus currentStatus,
                                              UUID currentResponsibleMembershipId,
                                              UUID actorMembershipId) {
        if (currentStatus != OperationalExceptionStatus.CLAIMED || actorMembershipId == null
                || !actorMembershipId.equals(currentResponsibleMembershipId)) return Optional.empty();
        return Optional.of(new Transition(OperationalExceptionStatus.UNDER_REVIEW,
                currentResponsibleMembershipId, actorMembershipId, "DRIVER_REVIEW_STARTED"));
    }
}
