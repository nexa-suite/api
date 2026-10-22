package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

public record BuyerClientAccountRegistrationCommand(
		ClientAccountType accountType,
		String businessName,
		String commercialName,
		String countryCode,
		String taxType,
		String taxValue,
		String phone,
		String deliveryProfile) {
}
