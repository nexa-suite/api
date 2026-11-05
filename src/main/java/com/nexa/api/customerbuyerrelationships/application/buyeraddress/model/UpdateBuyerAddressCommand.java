package com.nexa.api.customerbuyerrelationships.application.buyeraddress.model;

import com.nexa.api.customerbuyerrelationships.contract.Address;

public record UpdateBuyerAddressCommand(String label, Address address) { }
