package com.nexa.api.inventoryavailability.application.service;

import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.PhysicalAllocationSubstitutionRequests;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Records a reasoned request for a controlled physical-lot substitution decision. */
@Service
@Profile("!test")
public class PhysicalAllocationSubstitutionService {
    private final PhysicalAllocationSubstitutionRequests requests;
    private final Clock clock;

    public PhysicalAllocationSubstitutionService(PhysicalAllocationSubstitutionRequests requests, Clock clock) {
        this.requests = Objects.requireNonNull(requests, "Substitution requests are required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public PhysicalAllocationSubstitutionRequests.Result request(
            CurrentAccessContext context,
            UUID fulfillmentId,
            UUID allocationId,
            UUID physicalAllocationLineId,
            UUID expectedLotId,
            UUID alternativeLotId,
            BigDecimal quantity,
            String unit,
            String reason,
            String idempotencyKey,
            long expectedAllocationVersion) {
        context.requirePermission(PermissionKey.INVENTORY_ADJUST);
        if (fulfillmentId == null || allocationId == null || physicalAllocationLineId == null
                || expectedLotId == null || alternativeLotId == null || quantity == null
                || unit == null || reason == null || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        String key = idempotencyKey.trim();
        if (key.length() > 160 || key.chars().anyMatch(Character::isISOControl)) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        String requestHash = requestHash(fulfillmentId, allocationId, physicalAllocationLineId,
                expectedLotId, alternativeLotId, quantity, unit, reason, expectedAllocationVersion);
        return requests.request(new PhysicalAllocationSubstitutionRequests.Request(
                context.tenantId().value(), context.workspaceId().value(), fulfillmentId, allocationId,
                physicalAllocationLineId, expectedLotId, alternativeLotId, quantity, unit, reason,
                context.membershipId().value(), key, requestHash, expectedAllocationVersion, clock.instant()));
    }

    private static String requestHash(UUID fulfillmentId, UUID allocationId, UUID lineId,
                                      UUID expectedLotId, UUID alternativeLotId, BigDecimal quantity,
                                      String unit, String reason, long expectedVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                write(output, fulfillmentId.toString());
                write(output, allocationId.toString());
                write(output, lineId.toString());
                write(output, expectedLotId.toString());
                write(output, alternativeLotId.toString());
                write(output, quantity.toPlainString());
                write(output, unit);
                write(output, reason);
                output.writeLong(expectedVersion);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to identify lot substitution request", exception);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
