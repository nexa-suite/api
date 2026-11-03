package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds a Logistics-owned filter from dispatch IDs authorized through Warehouse. */
final class DispatchWarehouseVisibility {
    private DispatchWarehouseVisibility() { }

    static Predicate forDispatches(String alias, Set<UUID> visibleDispatchIds) {
        if (visibleDispatchIds == null || visibleDispatchIds.isEmpty()) {
            return new Predicate(" and false", List.of());
        }
        String ids = visibleDispatchIds.stream().sorted().map(UUID::toString)
                .collect(java.util.stream.Collectors.joining(","));
        return new Predicate(" and " + alias + ".id = any(string_to_array(?::text, ',')::uuid[])",
                List.of(ids));
    }

    static Predicate unrestricted() {
        return new Predicate("", List.of());
    }

    record Predicate(String sql, List<Object> arguments) {
        Predicate {
            arguments = List.copyOf(arguments);
        }
    }
}
