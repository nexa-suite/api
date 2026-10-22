package com.nexa.api.customerbuyerrelationships.application.buyeraddress.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;

import java.util.UUID;

public record BuyerAddressView(UUID id, String label, Address address, boolean defaultAddress,
                               boolean active, long version) { }
