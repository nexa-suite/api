package com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.time.LocalTime;

/** Narrow BC-01 commands for warehouse settings that remain centrally owned. */
public interface WarehouseOperationalSettingsCommands {
    Snapshot replaceHours(CurrentAccessContext context, LocalTime startsAt, LocalTime endsAt,
                          long expectedSettingsVersion, String correlationId);

    Snapshot replaceSelectionPolicy(CurrentAccessContext context, String selectionPolicy,
                                    long expectedSettingsVersion, String correlationId);

    record Snapshot(String selectionPolicy, LocalTime startsAt, LocalTime endsAt, long version) {
        public Snapshot {
            if (selectionPolicy == null || startsAt == null || endsAt == null || version < 0) {
                throw new IllegalArgumentException("Warehouse operational settings snapshot is invalid");
            }
        }
    }
}
