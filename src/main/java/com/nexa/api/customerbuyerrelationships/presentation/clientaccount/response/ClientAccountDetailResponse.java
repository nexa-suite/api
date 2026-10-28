package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

public record ClientAccountDetailResponse(String id, String code, String businessName, String commercialName,
		String countryCode, String taxType, String taxValue, String segment, String contactPerson,
		String contactEmail, String phone, String deliveryProfile, String paymentCondition, String status,
		String buyerMembershipId, long version, ClientAccountType accountType) { }
