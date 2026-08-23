package com.nexa.api.customerrelationships.application.clientaccountaddress.model;

import com.nexa.api.customerrelationships.contract.Address;

public record UpdateClientAccountAddressCommand(String label, Address address) { }
