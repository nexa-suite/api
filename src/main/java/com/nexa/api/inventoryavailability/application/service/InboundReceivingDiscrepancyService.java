package com.nexa.api.inventoryavailability.application.service;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import com.nexa.api.inventoryavailability.application.WarehouseOperationsService;
import com.nexa.api.inventoryavailability.application.publicapi.InboundReceivingDiscrepancyCases;
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

/** Records an inbound physical observation without accepting or mutating stock. */
@Service
@Profile("!test")
public class InboundReceivingDiscrepancyService {
    public static final String EVIDENCE_SUBJECT_TYPE = "INBOUND_RECEIVING_DISCREPANCY";

    private final InboundReceivingDiscrepancyCases cases;
    private final BusinessEvidenceQuery evidence;
    private final Clock clock;

    public InboundReceivingDiscrepancyService(InboundReceivingDiscrepancyCases cases,
                                               BusinessEvidenceQuery evidence,
                                               Clock clock) {
        this.cases = Objects.requireNonNull(cases, "Inbound discrepancy cases are required");
        this.evidence = Objects.requireNonNull(evidence, "Business evidence query is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    @Transactional
    public InboundReceivingDiscrepancyCases.CaseFact create(
            CurrentAccessContext context,
            UUID warehouseId,
            UUID expectedSkuId,
            UUID observedSkuId,
            String expectedBatchReference,
            String observedBatchReference,
            BigDecimal expectedQuantity,
            BigDecimal observedQuantity,
            String unit,
            String reason,
            String observationNotes,
            String idempotencyKey) {
        context.requirePermission(PermissionKey.INVENTORY_RECEIVE);
        String key = normalizedRequired(idempotencyKey, 160);
        String normalizedUnit = normalizedRequired(unit, 32).toUpperCase(java.util.Locale.ROOT);
        String normalizedReason = normalizedRequired(reason, 2_000);
        String expectedBatch = normalizedOptional(expectedBatchReference, 160);
        String observedBatch = normalizedOptional(observedBatchReference, 160);
        String notes = normalizedOptional(observationNotes, 2_000);
        if (warehouseId == null || observedSkuId == null || expectedQuantity == null
                || expectedQuantity.signum() < 0 || expectedQuantity.scale() > 4
                || observedQuantity == null || observedQuantity.signum() < 0 || observedQuantity.scale() > 4) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        String requestHash = hash("create", warehouseId.toString(), expectedSkuId == null ? "" : expectedSkuId.toString(), observedSkuId.toString(),
                nullable(expectedBatch), nullable(observedBatch), expectedQuantity.toPlainString(), observedQuantity.toPlainString(),
                normalizedUnit, normalizedReason, nullable(notes));
        return cases.create(new InboundReceivingDiscrepancyCases.CreateRequest(
                context.tenantId().value(), context.workspaceId().value(), warehouseId, expectedSkuId,
                observedSkuId, expectedBatch, observedBatch, expectedQuantity, observedQuantity,
                normalizedUnit, normalizedReason, notes, context.membershipId().value(), key,
                requestHash, clock.instant()));
    }

    @Transactional
    public InboundReceivingDiscrepancyCases.CaseFact submit(
            CurrentAccessContext context,
            UUID caseId,
            UUID evidenceObjectId,
            long expectedVersion,
            String idempotencyKey) {
        context.requirePermission(PermissionKey.INVENTORY_RECEIVE);
        context.requirePermission(PermissionKey.DOCUMENT_UPLOAD);
        context.requirePermission(PermissionKey.DOCUMENT_READ);
        String key = normalizedRequired(idempotencyKey, 160);
        if (caseId == null || evidenceObjectId == null || expectedVersion < 0) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        if (!evidence.isAvailableForSubject(context.tenantId().value(), context.workspaceId().value(),
                evidenceObjectId, EVIDENCE_SUBJECT_TYPE, caseId)) {
            throw new WarehouseOperationsService.WarehouseException("BUSINESS_EVIDENCE_NOT_AVAILABLE", false);
        }
        String requestHash = hash("submit", caseId.toString(), evidenceObjectId.toString(),
                Long.toString(expectedVersion));
        return cases.submit(new InboundReceivingDiscrepancyCases.SubmitRequest(
                context.tenantId().value(), context.workspaceId().value(), caseId, evidenceObjectId,
                context.membershipId().value(), expectedVersion, key, requestHash, clock.instant()));
    }

    private static String normalizedRequired(String value, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        return normalized;
    }

    private static String normalizedOptional(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > max || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new WarehouseOperationsService.WarehouseException("INVALID_REQUEST", false);
        }
        return normalized;
    }

    private static String hash(String... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(1);
                for (String value : values) {
                    byte[] field = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    output.writeInt(field.length);
                    output.write(field);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to identify inbound discrepancy command", exception);
        }
    }

    private static String nullable(String value) { return value == null ? "" : value; }
}
