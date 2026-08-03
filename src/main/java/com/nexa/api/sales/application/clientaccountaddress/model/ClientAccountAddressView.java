package com.nexa.api.sales.application.clientaccountaddress.model;

import com.nexa.api.sales.domain.model.address.Address;

import java.util.UUID;

public record ClientAccountAddressView(UUID id, String clientAccountId, String label, Address address,
                                       boolean defaultAddress, boolean active, long version) { }
