package com.nexa.api.inventoryavailability.application.port;

import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.StockTemperatureEvidence;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureEvidenceSubject;
import com.nexa.api.inventoryavailability.domain.model.temperatureevidence.TemperatureUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface StockTemperatureEvidencePersistencePort {
    StockTemperatureEvidence record(RecordStockTemperatureEvidence request);

    record RecordStockTemperatureEvidence(
            UUID tenantId,
            UUID workspaceId,
            UUID actorMembershipId,
            TemperatureEvidenceSubject subjectType,
            UUID lotId,
            UUID warehouseId,
            BigDecimal value,
            TemperatureUnit unit,
            Instant observedAt,
            Instant recordedAt,
            String idempotencyKey,
            String requestHash) { }
}
