package com.nexa.api.customerbuyerrelationships.application.fieldvisit.model;

import java.time.Instant;

/** Immutable relationship evidence, never a commercial commitment or a location history. */
public record FieldVisitEvidence(String id, String clientAccountId, String recordedByMembershipId,
        long customerVersion, String purpose, String outcome, Instant occurredAt, Instant recordedAt) { }
