package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

/** Company account data for the authenticated Buyer, without another membership's personal contact data. */
public record BuyerClientAccountSelfResponse(String id, String code, String businessName, String commercialName,
		String countryCode, String taxType, String taxValue, String segment, String deliveryProfile,
		String paymentCondition, String status, long version, ClientAccountType accountType) { }
