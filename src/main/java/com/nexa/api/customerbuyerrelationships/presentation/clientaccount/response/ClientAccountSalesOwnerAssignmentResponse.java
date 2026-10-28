package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

/** Seller assignment result contains account/version and membership reference only. */
public record ClientAccountSalesOwnerAssignmentResponse(String clientAccountId,
                                                        String salesOwnerMembershipId,
                                                        long accountVersion) { }
