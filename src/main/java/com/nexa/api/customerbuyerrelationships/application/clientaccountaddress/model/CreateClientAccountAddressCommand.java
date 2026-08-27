package com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;

public record CreateClientAccountAddressCommand(String label, Address address, boolean defaultAddress) { }
