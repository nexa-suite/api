package com.nexa.api.tenantaccessgovernance.iam.application.model;

import java.time.Instant;

public final class LegalAcceptanceModels {
    private LegalAcceptanceModels() { }
    public record Evidence(String digest, Instant acceptedAt) { }
    public record Request(String documentId, String version, String contentDigest) { }
    public record View(String documentId, String version, String content, String contentDigest, boolean accepted, Instant acceptedAt) { }
}
