package com.nexa.api.fulfillmentdelivery.domain.operationalexception;

import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentSeverity;
import com.nexa.api.fulfillmentdelivery.domain.incident.IncidentType;

import java.util.Optional;

/** Maps only incident facts with an explicit Owner-approved V1 classification. */
public final class OperationalExceptionSourceClassifier {
    private OperationalExceptionSourceClassifier() { }

    public static Optional<OperationalExceptionSeverity> classify(IncidentType type, IncidentSeverity severity) {
        if (type == IncidentType.DELAY) return Optional.of(OperationalExceptionSeverity.WARNING);
        if (type == IncidentType.TEMPERATURE_EXCURSION && severity == IncidentSeverity.CRITICAL) {
            return Optional.of(OperationalExceptionSeverity.CRITICAL);
        }
        return Optional.empty();
    }

    public static OperationalExceptionSeverity classify(DriverDeliveryIncidentType type) {
        if (type == null) throw new IllegalArgumentException("Driver incident type is required");
        return switch (type) {
            case DELAY, INCOMPLETE_INSTRUCTION -> OperationalExceptionSeverity.WARNING;
            case ACCESS_BLOCKED, CUSTOMER_UNAVAILABLE, DELIVERY_NOT_EXECUTABLE -> OperationalExceptionSeverity.BLOCKING;
            case TEMPERATURE_EXCURSION, SAFETY_COMPROMISING_DAMAGE -> OperationalExceptionSeverity.CRITICAL;
        };
    }
}
