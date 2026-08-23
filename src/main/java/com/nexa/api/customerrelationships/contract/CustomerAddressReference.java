package com.nexa.api.customerrelationships.contract;

/** Immutable address snapshot exposed to Sales Commitment without aggregate authority. */
public record CustomerAddressReference(String id, String label, Address address, boolean defaultAddress) {
}
