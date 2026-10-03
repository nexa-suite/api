package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

import java.util.Optional;
import java.util.UUID;

/** Pure Driver transitions; completion is limited to warnings within Driver authority. */
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
    public static Optional<Transition> resolveWarning(OperationalExceptionStatus currentStatus,
            UUID responsibleMembershipId, UUID actorMembershipId, String type, String severity) {
        if (currentStatus != OperationalExceptionStatus.UNDER_REVIEW || actorMembershipId == null
                || !actorMembershipId.equals(responsibleMembershipId) || !"WARNING".equals(severity)) {
            return Optional.empty();
        }
        String code = switch (type == null ? "" : type) {
            case "DELAY" -> "DRIVER_DELAY_ADDRESSED";
            case "INCOMPLETE_INSTRUCTION" -> "DRIVER_INSTRUCTION_CLARIFIED";
            default -> null;
        };
        return code == null ? Optional.empty() : Optional.of(new Transition(OperationalExceptionStatus.RESOLVED,
                responsibleMembershipId, actorMembershipId, code));
    }

    public static Optional<Transition> closeWarning(OperationalExceptionStatus currentStatus,
            UUID responsibleMembershipId, UUID actorMembershipId, String severity) {
        if (currentStatus != OperationalExceptionStatus.RESOLVED || actorMembershipId == null
                || !actorMembershipId.equals(responsibleMembershipId) || !"WARNING".equals(severity)) {
            return Optional.empty();
        }
        return Optional.of(new Transition(OperationalExceptionStatus.CLOSED, responsibleMembershipId,
                actorMembershipId, "DRIVER_WARNING_CLOSED"));
    }

}
