package com.nexa.api.inventoryavailability.infrastructure.persistence;

import com.nexa.api.salescommitment.application.purchaserequest.port.CatalogItemSnapshotLookupPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.WarehouseConfigurationPersistencePort;
import com.nexa.api.inventoryavailability.application.port.WarehouseOperationalSettingsPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexa.api.inventoryavailability.infrastructure.persistence.WarehousePersistenceSupport.*;

/** Cohesive JDBC adapter for warehouse and storage-zone configuration. */
@Repository
@Profile("!test")
public class WarehouseConfigurationPersistenceAdapter extends WarehouseJdbcSupport
        implements WarehouseConfigurationPersistencePort {

    @Autowired
    public WarehouseConfigurationPersistenceAdapter(
            JdbcTemplate jdbc,
            ChangeEventPersistencePort changeFeed,
            CatalogItemSnapshotLookupPort catalog,
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            WarehouseOperationalSettingsPort operationalSettings) {
        super(jdbc, changeFeed, catalog, transactionManager, operationalSettings);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.WarehouseSummary> warehouses(
            CurrentAccessContext context, int page, int size, String sort) {
        requireRead(context);
        pageCheck(page, size);
        String order = sort(sort, Map.of("code", "code", "name", "name", "status", "status",
                "createdAt", "created_at", "updatedAt", "updated_at"), "code");
        List<WarehouseOperationsService.WarehouseSummary> items = jdbc.query(
                "select id,code,name,address,status,version from warehouse.warehouse "
                        + "where tenant_id=? and workspace_id=? order by " + order + ",id asc limit ? offset ?",
                (rs, row) -> WarehousePersistenceSupport.warehouse(rs), tenant(context), workspace(context), size, page * size);
        return new WarehouseOperationsService.Page<>(items, page, size,
                count("select count(*) from warehouse.warehouse where tenant_id=? and workspace_id=?",
                        tenant(context), workspace(context)));
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.WarehouseSummary warehouse(CurrentAccessContext context, String id) {
        requireRead(context);
        return jdbc.query("select id,code,name,address,status,version from warehouse.warehouse "
                                + "where tenant_id=? and workspace_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.warehouse(rs), tenant(context), workspace(context), uuid(id))
                .stream().findFirst().orElseThrow(() -> error("WAREHOUSE_NOT_FOUND", true));
    }

    public WarehouseOperationsService.WarehouseSummary createWarehouse(
            CurrentAccessContext context, String code, String name, String address) {
        requireWrite(context);
        String normalizedCode = boundedUpper(code, "code", 32);
        String normalizedName = bounded(name, "name", 160);
        String normalizedAddress = boundedNullable(address, "address", 2000);
        UUID id = UUID.randomUUID();
        Timestamp now = now();
        checkUpdated(jdbc.update("insert into warehouse.warehouse(id,tenant_id,workspace_id,code,name,address,status,created_at,updated_at) "
                        + "values (?,?,?,?,?,?,'ACTIVE',?,?)", id, tenant(context), workspace(context), normalizedCode,
                normalizedName, normalizedAddress, now, now), "warehouse insert");
        appendEvent(context, id, "warehouse.warehouse.created", "warehouse");
        return warehouse(context, id.toString());
    }

    public WarehouseOperationsService.WarehouseSummary updateWarehouse(
            CurrentAccessContext context, String id, String name, String address, String status, long expected) {
        requireWrite(context);
        String normalizedStatus = status == null ? null : enumValue(status, "status", "ACTIVE", "SUSPENDED");
        String normalizedName = name == null ? null : bounded(name, "name", 160);
        String normalizedAddress = address == null ? null : boundedNullable(address, "address", 2000);
        checkUpdated(jdbc.update("update warehouse.warehouse set name=coalesce(?,name),address=coalesce(?,address),"
                        + "status=coalesce(?,status),updated_at=?,version=version+1 where tenant_id=? and workspace_id=? and id=? and version=?",
                normalizedName, normalizedAddress, normalizedStatus, now(), tenant(context), workspace(context), uuid(id), expected),
                "warehouse update", "CONCURRENCY_CONFLICT");
        appendEvent(context, uuid(id), "warehouse.warehouse.updated", "warehouse");
        return warehouse(context, id);
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.OperationalProfile operationalProfile(CurrentAccessContext context, String id) {
        requireRead(context);
        WarehouseOperationsService.WarehouseSummary summary = warehouse(context, id);
        return operationalProfile(summary, settings(context), coordinates(context, uuid(id)));
    }

    public WarehouseOperationsService.OperationalProfile updateOperationalProfile(
            CurrentAccessContext context, String id, WarehouseOperationsService.OperationalPatch patch, long expected) {
        requireWrite(context);
        if (patch == null) throw error("INVALID_REQUEST", false);
        UUID warehouseId = uuid(id);
        WarehouseOperationsService.WarehouseSummary current = warehouseForUpdate(context, warehouseId);
        if (current.version() != expected) throw error("CONCURRENCY_CONFLICT", false);
        WarehouseOperationalSettingsPort.Snapshot currentSettings = settings(context);
        String status = patch.status() == null ? current.status() : enumValue(patch.status(), "status", "ACTIVE", "SUSPENDED");
        if (patch.serviceable() != null) {
            String serviceableStatus = patch.serviceable() ? "ACTIVE" : "SUSPENDED";
            if (patch.status() != null && !serviceableStatus.equals(status)) throw error("INVALID_REQUEST", false);
            status = serviceableStatus;
        }
        String name = patch.name() == null ? current.name() : bounded(patch.name(), "name", 160);
        String address = patch.address() == null ? current.address() : boundedNullable(patch.address(), "address", 2000);
        String selection = patch.selectionPolicy() == null ? currentSettings.selectionPolicy()
                : enumValue(patch.selectionPolicy(), "selectionPolicy", "MANUAL", "PREFERRED");
        LocalTime startsAt = patch.operatingHoursStart() == null ? currentSettings.startsAt() : patch.operatingHoursStart();
        LocalTime endsAt = patch.operatingHoursEnd() == null ? currentSettings.endsAt() : patch.operatingHoursEnd();
        new com.nexa.api.inventoryavailability.domain.model.warehouse.WarehouseHours(startsAt, endsAt);
        Coordinates currentCoordinates = coordinates(context, warehouseId);
        BigDecimal latitude = patch.latitude() == null ? currentCoordinates.latitude() : patch.latitude();
        BigDecimal longitude = patch.longitude() == null ? currentCoordinates.longitude() : patch.longitude();
        validateCoordinates(latitude, longitude);
        boolean warehouseChanged = !java.util.Objects.equals(name, current.name())
                || !java.util.Objects.equals(address, current.address()) || !java.util.Objects.equals(status, current.status());
        boolean settingsChanged = !selection.equals(currentSettings.selectionPolicy())
                || !startsAt.equals(currentSettings.startsAt()) || !endsAt.equals(currentSettings.endsAt());
        boolean coordinatesChanged = !java.util.Objects.equals(latitude, currentCoordinates.latitude())
                || !java.util.Objects.equals(longitude, currentCoordinates.longitude());
        if (!warehouseChanged && !settingsChanged && !coordinatesChanged) return operationalProfile(current, currentSettings, currentCoordinates);
        Timestamp changedAt = now();
        checkUpdated(jdbc.update("update warehouse.warehouse set name=?,address=?,status=?,updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and id=? and version=?", name, address, status, changedAt,
                tenant(context), workspace(context), warehouseId, expected), "warehouse operational profile update", "CONCURRENCY_CONFLICT");
        if (settingsChanged) {
            if (operationalSettings == null) throw error("OPERATIONAL_SETTINGS_NOT_FOUND", true);
            if (operationalSettings.update(tenant(context).toString(), workspace(context).toString(), selection,
                    startsAt, endsAt, currentSettings.version()) != 1) throw error("CONCURRENCY_CONFLICT", false);
        }
        if (coordinatesChanged) upsertCoordinates(context, warehouseId, latitude, longitude, changedAt);
        appendEvent(context, warehouseId, "warehouse.operational-profile.updated", "warehouse");
        return operationalProfile(context, id);
    }

    @Transactional(readOnly = true)
    public List<WarehouseOperationsService.BuyerWarehouse> buyerWarehouses(CurrentAccessContext context) {
        context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.TRACKING_BUYER_READ);
        WarehouseOperationalSettingsPort.Snapshot currentSettings = settings(context);
        List<WarehouseOperationsService.WarehouseSummary> summaries = jdbc.query(
                "select id,code,name,address,status,version from warehouse.warehouse where tenant_id=? and workspace_id=? "
                        + "and status='ACTIVE' order by code asc,id asc", (rs, row) -> WarehousePersistenceSupport.warehouse(rs), tenant(context), workspace(context));
        return summaries.stream().map(summary -> buyerProjection(context, summary, currentSettings)).toList();
    }

    @Transactional(readOnly = true)
    public WarehouseOperationsService.Page<WarehouseOperationsService.ZoneSummary> zones(
            CurrentAccessContext context, String warehouseId, int page, int size) {
        requireRead(context);
        pageCheck(page, size);
        UUID warehouseIdValue = uuid(warehouseId);
        if (!exists("select 1 from warehouse.warehouse where tenant_id=? and workspace_id=? and id=?",
                tenant(context), workspace(context), warehouseIdValue)) throw error("WAREHOUSE_NOT_FOUND", true);
        List<WarehouseOperationsService.ZoneSummary> items = jdbc.query(
                "select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version "
                        + "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? "
                        + "order by code asc,id asc limit ? offset ?", (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context),
                warehouseIdValue, size, page * size);
        return new WarehouseOperationsService.Page<>(items, page, size, count(
                "select count(*) from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=?",
                tenant(context), workspace(context), warehouseIdValue));
    }

    public WarehouseOperationsService.ZoneSummary createZone(CurrentAccessContext context, String warehouseId, String code,
                                                              String name, String type, BigDecimal min, BigDecimal max) {
        requireWrite(context);
        UUID warehouse = uuid(warehouseId);
        requireActiveWarehouse(context, warehouse);
        String normalizedType = enumValue(type, "type", "AMBIENT", "CHILLED", "FROZEN", "QUARANTINE");
        validateTemperatureRange(min, max);
        UUID id = UUID.randomUUID();
        Timestamp now = now();
        checkUpdated(jdbc.update("insert into warehouse.storage_zone(id,tenant_id,workspace_id,warehouse_id,code,name,zone_type,"
                        + "temperature_min,temperature_max,status,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,'ACTIVE',?,?)",
                id, tenant(context), workspace(context), warehouse, boundedUpper(code, "code", 32), bounded(name, "name", 160),
                normalizedType, min, max, now, now), "zone insert");
        appendEvent(context, id, "warehouse.zone.created", "zone");
        return zone(context, warehouse, id);
    }

    public WarehouseOperationsService.ZoneSummary updateZone(CurrentAccessContext context, String warehouseId, String zoneId,
                                                              String name, BigDecimal min, BigDecimal max, String status, long expected) {
        requireWrite(context);
        UUID warehouse = uuid(warehouseId);
        UUID zone = uuid(zoneId);
        requireActiveWarehouse(context, warehouse);
        WarehouseOperationsService.ZoneSummary current = jdbc.query(
                        "select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version "
                                + "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context), warehouse, zone)
                .stream().findFirst().orElseThrow(() -> error("STORAGE_ZONE_NOT_FOUND", true));
        validateTemperatureRange(min == null ? current.temperatureMin() : min, max == null ? current.temperatureMax() : max);
        String normalizedStatus = status == null ? null : enumValue(status, "status", "ACTIVE", "SUSPENDED");
        checkUpdated(jdbc.update("update warehouse.storage_zone set name=coalesce(?,name),temperature_min=coalesce(?,temperature_min),"
                        + "temperature_max=coalesce(?,temperature_max),status=coalesce(?,status),updated_at=?,version=version+1 "
                        + "where tenant_id=? and workspace_id=? and warehouse_id=? and id=? and version=?",
                name == null ? null : bounded(name, "name", 160), min, max, normalizedStatus, now(), tenant(context), workspace(context),
                warehouse, zone, expected), "zone update", "CONCURRENCY_CONFLICT");
        appendEvent(context, zone, "warehouse.zone.updated", "zone");
        return zone(context, warehouse, zone);
    }

    private WarehouseOperationsService.ZoneSummary zone(CurrentAccessContext context, UUID warehouseId, UUID id) {
        return jdbc.query("select id,warehouse_id,code,name,zone_type,temperature_min,temperature_max,status,version "
                                + "from warehouse.storage_zone where tenant_id=? and workspace_id=? and warehouse_id=? and id=?",
                        (rs, row) -> WarehousePersistenceSupport.zone(rs), tenant(context), workspace(context), warehouseId, id)
                .stream().findFirst().orElseThrow(() -> error("STORAGE_ZONE_NOT_FOUND", true));
    }

    private void requireRead(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_READ); }
    private void requireWrite(CurrentAccessContext context) { context.requirePermission(com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission.WAREHOUSE_WRITE); }

    private static void validateCoordinates(BigDecimal latitude, BigDecimal longitude) {
        if ((latitude == null) != (longitude == null)
                || latitude != null && (latitude.compareTo(BigDecimal.valueOf(-90)) < 0
                || latitude.compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.compareTo(BigDecimal.valueOf(-180)) < 0
                || longitude.compareTo(BigDecimal.valueOf(180)) > 0)) throw error("INVALID_REQUEST", false);
    }
}
