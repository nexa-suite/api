package com.nexa.api.sales.application.clientaccount.model;

/**
 * Buyer membership option exposed to sales workflows.
 *
 * The API intentionally returns a human-readable label with the opaque key so
 * clients never need to ask an operator to type an internal membership UUID.
 */
public record BuyerMembershipCandidate(String id, String email, String displayName) { }
