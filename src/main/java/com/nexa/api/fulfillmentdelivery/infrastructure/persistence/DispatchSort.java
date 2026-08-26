package com.nexa.api.fulfillmentdelivery.infrastructure.persistence;

import com.nexa.api.fulfillmentdelivery.application.LogisticsOperationsService.LogisticsException;

/**
 * Closed dispatch sort contract. Parsed request values select server-owned SQL
 * constants; they never become SQL fragments.
 */
final class DispatchSort {
    private final Key key;
    private final Direction direction;

    private DispatchSort(Key key, Direction direction) {
        this.key = key;
        this.direction = direction;
    }

    static DispatchSort parse(String value) {
        String candidate = value == null || value.isBlank() ? "updatedAt" : value;
        String[] parts = candidate.split(",", -1);
        if (parts.length > 2) throw invalid();
        Key key = Key.from(parts[0]);
        Direction direction = parts.length == 2 ? Direction.from(parts[1]) : null;
        return new DispatchSort(key, direction);
    }

    String sql() {
        return key.sql(direction);
    }

    private static LogisticsException invalid() {
        return new LogisticsException("INVALID_INVENTORY_SORT", false);
    }

    private enum Direction {
        ASC("asc"), DESC("desc");

        private final String sql;

        Direction(String sql) {
            this.sql = sql;
        }

        static Direction from(String value) {
            for (Direction direction : values()) {
                if (direction.sql.equalsIgnoreCase(value)) return direction;
            }
            throw invalid();
        }
    }

    private enum Key {
        UPDATED_AT("updatedAt", Direction.DESC, "d.updated_at %s,d.id %s"),
        DISPATCH_NUMBER("dispatchNumber", Direction.ASC, "d.dispatch_number %s,d.id %s"),
        DELIVERY_WINDOW_START("deliveryWindowStart", Direction.ASC,
                "d.delivery_window_start %s nulls last,d.id %s"),
        PRIORITY("priority", Direction.ASC,
                "d.priority %s,d.delivery_window_start %s nulls last,d.id %s"),
        STATUS("status", Direction.ASC, "d.status %s,d.id %s");

        private final String requestValue;
        private final Direction defaultDirection;
        private final String sqlTemplate;

        Key(String requestValue, Direction defaultDirection, String sqlTemplate) {
            this.requestValue = requestValue;
            this.defaultDirection = defaultDirection;
            this.sqlTemplate = sqlTemplate;
        }

        static Key from(String value) {
            for (Key key : values()) {
                if (key.requestValue.equals(value)) return key;
            }
            throw invalid();
        }

        String sql(Direction requestedDirection) {
            Direction selected = requestedDirection == null ? defaultDirection : requestedDirection;
            return switch (this) {
                case UPDATED_AT, DISPATCH_NUMBER, DELIVERY_WINDOW_START, STATUS ->
                        sqlTemplate.formatted(selected.sql, selected.sql);
                case PRIORITY -> sqlTemplate.formatted(selected.sql, selected.sql, selected.sql);
            };
        }
    }
}
