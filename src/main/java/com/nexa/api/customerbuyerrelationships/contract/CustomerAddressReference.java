package com.nexa.api.customerbuyerrelationships.contract;

/** Immutable address snapshot exposed to Sales Commitment without aggregate authority. */
public record CustomerAddressReference(String id, String label, Address address, boolean defaultAddress) {
}
