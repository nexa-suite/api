package com.nexa.api.sales.application.clientaccountaddress.model;

import com.nexa.api.sales.domain.model.address.Address;

public record CreateClientAccountAddressCommand(String label, Address address, boolean defaultAddress) { }
