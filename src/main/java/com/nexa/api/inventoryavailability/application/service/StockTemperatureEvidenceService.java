package com.nexa.api.inventoryavailability.application.service;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.port.StockTemperatureEvidencePersistencePort;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.StockTemperatureEvidence;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureEvidenceSubject;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureEvidenceStatus;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureUnit;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.model.access.Permission;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Application boundary for append-only stock temperature evidence. */
@Service
@Profile("!test")
public class StockTemperatureEvidenceService {
    private final StockTemperatureEvidencePersistencePort evidence;
    private final Clock clock;

    public StockTemperatureEvidenceService(StockTemperatureEvidencePersistencePort evidence, Clock clock) {
        this.evidence = Objects.requireNonNull(evidence, "Temperature evidence persistence is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public StockTemperatureEvidence record(CurrentAccessContext context, UUID lotId, UUID warehouseId,
                                           BigDecimal value, String unit, Instant observedAt,
                                           String idempotencyKey) {
        Objects.requireNonNull(context, "Access context is required");
        context.requirePermission(Permission.WAREHOUSE_WRITE);
        if ((lotId == null) == (warehouseId == null)) throw invalid();
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 160) {
            throw new WarehouseOperationsService.WarehouseException("IDEMPOTENCY_KEY_REQUIRED", false);
        }
        if (value == null || observedAt == null) throw invalid();

        TemperatureUnit temperatureUnit;
        try {
            temperatureUnit = TemperatureUnit.from(unit);
            StockTemperatureEvidence.validateMeasurement(value, temperatureUnit, observedAt);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }

        TemperatureEvidenceSubject subjectType = lotId == null
                ? TemperatureEvidenceSubject.WAREHOUSE : TemperatureEvidenceSubject.LOT;
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        UUID actorMembershipId = context.membershipId().value();
        Instant recordedAt = clock.instant();
        String requestHash = hash(subjectType, lotId, warehouseId, value, temperatureUnit, observedAt);
        return evidence.record(new StockTemperatureEvidencePersistencePort.RecordStockTemperatureEvidence(
                tenantId, workspaceId, actorMembershipId, subjectType, lotId, warehouseId, value,
                temperatureUnit, observedAt, recordedAt, idempotencyKey, requestHash));
    }

    private static String hash(TemperatureEvidenceSubject subjectType, UUID lotId, UUID warehouseId,
                               BigDecimal value, TemperatureUnit unit, Instant observedAt) {
        String canonical = subjectType + "|" + Objects.toString(lotId, "") + "|"
                + Objects.toString(warehouseId, "") + "|" + value.stripTrailingZeros().toPlainString()
                + "|" + unit.name() + "|" + observedAt;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static WarehouseOperationsService.WarehouseException invalid() {
        return new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
    }
}
