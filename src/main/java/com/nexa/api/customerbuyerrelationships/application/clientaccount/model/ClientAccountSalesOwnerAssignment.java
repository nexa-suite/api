package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

/** Narrow result for a seller assignment or transfer; contains no Buyer PII. */
public record ClientAccountSalesOwnerAssignment(String clientAccountId, String salesOwnerMembershipId,
                                               long accountVersion) { }
