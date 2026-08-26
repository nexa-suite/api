package com.nexa.api.fulfillmentdelivery.domain.dispatchorder;

import java.util.UUID;

public record TransportAssignment(UUID responsibleMembershipId, String responsibleDisplayNameSnapshot,
                                  String vehicleReference, String routeName) {
    public TransportAssignment {
        if (responsibleMembershipId == null) throw new IllegalArgumentException("Responsible membership is required");
        responsibleDisplayNameSnapshot = required(responsibleDisplayNameSnapshot, 160, "Responsible display name");
        vehicleReference = optional(vehicleReference, 120);
        routeName = optional(routeName, 160);
    }

    private static String required(String value, int max, String label) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw new IllegalArgumentException(label + " is invalid");
        return value.trim();
    }
    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        if (value.trim().length() > max) throw new IllegalArgumentException("Assignment value is too long");
        return value.trim();
    }
}
