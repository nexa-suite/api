package com.nexa.api.fulfillmentdelivery.domain.proofofdelivery;

import java.time.Instant;

public record ProofOfDeliveryRecord(String receiverName, Instant completedAt, String notes,
                                    boolean photoEvidenceDeclared, boolean signatureEvidenceDeclared,
                                    ProofOfDeliveryStatus status) {
    public ProofOfDeliveryRecord {
        if (receiverName == null || receiverName.isBlank() || completedAt == null || status == null) throw new IllegalArgumentException("Proof of delivery is incomplete");
        receiverName = receiverName.trim(); notes = notes == null ? null : notes.trim();
        if (receiverName.length() > 160 || notes != null && notes.length() > 2000) throw new IllegalArgumentException("Proof of delivery text is too long");
        if (status != ProofOfDeliveryStatus.COMPLETED) throw new IllegalArgumentException("Only completed POD metadata is accepted");
    }
}
