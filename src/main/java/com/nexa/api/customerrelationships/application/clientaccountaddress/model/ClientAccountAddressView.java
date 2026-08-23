package com.nexa.api.customerrelationships.application.clientaccountaddress.model;

import com.nexa.api.customerrelationships.contract.Address;

import java.util.UUID;

public record ClientAccountAddressView(UUID id, String clientAccountId, String label, Address address,
                                       boolean defaultAddress, boolean active, long version) { }
