package com.nexa.api.salescommitment.application.purchaserequest.model;

import org.springframework.modulith.NamedInterface;

import java.time.Instant;

@NamedInterface("sales-public")
public record MaterialChangeProposalView(String id, String purchaseRequestId, String status,
        String proposedByMembershipId, String resolvedByMembershipId, String reason,
        MaterialChangeTerms originalTerms, MaterialChangeTerms proposedTerms,
        Instant proposedAt, Instant resolvedAt, long requestVersion, Long resolvedRequestVersion) { }
