package com.nexa.api.warehouse.infrastructure.persistence;

import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.warehouse.application.WarehouseOperationsService;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Shared, side-effect-free JDBC mapping and validation primitives for Warehouse adapters. */
final class WarehousePersistenceSupport {
    static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal MIN_TEMPERATURE = BigDecimal.valueOf(-1000);
    private static final BigDecimal MAX_TEMPERATURE = BigDecimal.valueOf(1000);

    private WarehousePersistenceSupport() { }

    static void validateTemperatureRange(BigDecimal min, BigDecimal max) {
        if (min != null && (min.compareTo(MIN_TEMPERATURE) <= 0 || min.compareTo(MAX_TEMPERATURE) >= 0)
                || max != null && (max.compareTo(MIN_TEMPERATURE) <= 0 || max.compareTo(MAX_TEMPERATURE) >= 0)
                || min != null && max != null && min.compareTo(max) > 0) {
            throw error("INVALID_REQUEST", false);
        }
    }

    static void validateTemperature(BigDecimal value) {
        if (value == null) return;
        if (!Double.isFinite(value.doubleValue())
                || value.compareTo(MIN_TEMPERATURE) <= 0 || value.compareTo(MAX_TEMPERATURE) >= 0) {
            throw error("INVALID_REQUEST", false);
        }
    }

    static String normalizedUnit(String value) {
        String unit = bounded(value, "unit", 32).toUpperCase(java.util.Locale.ROOT);
        if (!unit.matches("[A-Z0-9._/-]+")) throw error("INVALID_REQUEST", false);
        return unit;
    }

    static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) throw error("INVALID_REQUEST", false);
        return value.trim();
    }

    static String boundedNullable(String value, String field, int max) {
        if (value == null) return null;
        if (value.trim().length() > max) throw error("INVALID_REQUEST", false);
        return value.trim();
    }

    static String boundedUpper(String value, String field, int max) {
        return bounded(value, field, max).toUpperCase(java.util.Locale.ROOT);
    }

    static String enumValue(String value, String field, String... allowed) {
        String normalized = bounded(value, field, 32).toUpperCase(java.util.Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return normalized;
        throw error("INVALID_REQUEST", false);
    }

    static void requireIdempotency(String key) {
        if (key == null || key.isBlank() || key.length() > 160) throw error("IDEMPOTENCY_KEY_REQUIRED", false);
    }

    static void pageCheck(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) throw error("INVALID_REQUEST", false);
    }

    static String sort(String value, Map<String, String> allowed, String fallback) {
        if (value == null || value.isBlank()) return allowed.getOrDefault(fallback, fallback) + " asc";
        String[] parts = value.split(",", -1);
        if (parts.length > 2 || !allowed.containsKey(parts[0])
                || (parts.length == 2 && !parts[1].equals("asc") && !parts[1].equals("desc"))) {
            throw error("INVALID_INVENTORY_SORT", false);
        }
        return allowed.get(parts[0]) + (parts.length == 2 && parts[1].equals("desc") ? " desc" : " asc");
    }

    static UUID tenant(CurrentAccessContext context) { return UUID.fromString(context.tenantId().toString()); }
    static UUID workspace(CurrentAccessContext context) { return UUID.fromString(context.workspaceId().toString()); }

    static UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (RuntimeException exception) { throw error("INVALID_REQUEST", false); }
    }

    static UUID uuidRequired(String value, String field) {
        if (value == null || value.isBlank()) throw error("INVALID_REQUEST", false);
        return uuid(value);
    }

    static Timestamp now() { return Timestamp.from(Instant.now()); }

    static void checkUpdated(int count, String operation) { checkUpdated(count, operation, "INVALID_REQUEST"); }
    static void checkUpdated(int count, String operation, String code) {
        if (count != 1) throw error(code, false);
    }

    static WarehouseOperationsService.WarehouseException error(String code, boolean notFound) {
        return new WarehouseOperationsService.WarehouseException(code, notFound);
    }

    static WarehouseOperationsService.WarehouseSummary warehouse(ResultSet rs) throws java.sql.SQLException {
        return new WarehouseOperationsService.WarehouseSummary(rs.getObject("id").toString(), rs.getString("code"),
                rs.getString("name"), rs.getString("address"), rs.getString("status"), rs.getLong("version"));
    }

    static WarehouseOperationsService.ZoneSummary zone(ResultSet rs) throws java.sql.SQLException {
        return new WarehouseOperationsService.ZoneSummary(rs.getObject("id").toString(), rs.getObject("warehouse_id").toString(),
                rs.getString("code"), rs.getString("name"), rs.getString("zone_type"),
                rs.getBigDecimal("temperature_min"), rs.getBigDecimal("temperature_max"),
                rs.getString("status"), rs.getLong("version"));
    }

    static WarehouseOperationsService.LotSummary lot(ResultSet rs) throws java.sql.SQLException {
        BigDecimal onHand = rs.getBigDecimal("stock_quantity");
        BigDecimal reserved = rs.getBigDecimal("reserved_quantity");
        return new WarehouseOperationsService.LotSummary(rs.getObject("id").toString(),
                rs.getObject("warehouse_id").toString(), rs.getObject("zone_id").toString(),
                rs.getString("catalog_item_id"), rs.getString("batch_number"),
                rs.getObject("expiration_date", LocalDate.class), instant(rs, "received_at"),
                onHand, reserved, onHand.subtract(reserved), rs.getString("unit"),
                rs.getString("status"), rs.getLong("version"));
    }

    static WarehouseOperationsService.MovementSummary movement(ResultSet rs) throws java.sql.SQLException {
        return new WarehouseOperationsService.MovementSummary(rs.getObject("id").toString(),
                rs.getObject("lot_id").toString(), rs.getString("catalog_item_id"),
                rs.getString("movement_type"), rs.getBigDecimal("quantity"), rs.getString("unit"),
                rs.getBigDecimal("quantity_before"), rs.getBigDecimal("quantity_after"),
                rs.getBigDecimal("reserved_before"), rs.getBigDecimal("reserved_after"),
                rs.getString("reason"), instant(rs, "occurred_at"));
    }

    static Instant instant(ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static Instant instantNullable(ResultSet rs, String column) throws java.sql.SQLException {
        return instant(rs, column);
    }
}
