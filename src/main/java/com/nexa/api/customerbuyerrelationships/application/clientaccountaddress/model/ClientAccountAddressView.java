package com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;

import java.util.UUID;

public record ClientAccountAddressView(UUID id, String clientAccountId, String label, Address address,
                                       boolean defaultAddress, boolean active, long version) { }
