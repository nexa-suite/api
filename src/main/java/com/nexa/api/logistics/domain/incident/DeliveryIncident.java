package com.nexa.api.logistics.domain.incident;

import java.time.Instant;

public record DeliveryIncident(IncidentType type, IncidentSeverity severity, boolean buyerVisible,
                               String description, Instant occurredAt, String resolution) {
    public DeliveryIncident {
        if (type == null || severity == null || description == null || description.isBlank() || occurredAt == null) throw new IllegalArgumentException("Incident is incomplete");
        description = description.trim(); resolution = resolution == null ? null : resolution.trim();
        if (description.length() > 2000 || resolution != null && resolution.length() > 2000) throw new IllegalArgumentException("Incident text is too long");
    }
}
