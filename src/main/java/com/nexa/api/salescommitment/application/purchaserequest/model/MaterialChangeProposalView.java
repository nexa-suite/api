package com.nexa.api.salescommitment.application.purchaserequest.model;

import java.time.Instant;

public record MaterialChangeProposalView(String id, String purchaseRequestId, String status,
        String proposedByMembershipId, String resolvedByMembershipId, String reason,
        MaterialChangeTerms originalTerms, MaterialChangeTerms proposedTerms,
        Instant proposedAt, Instant resolvedAt, long requestVersion, Long resolvedRequestVersion) { }
