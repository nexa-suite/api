package com.nexa.api.sales.presentation.clientaccount.response;

public record ClientAccountDetailResponse(String id, String code, String businessName, String commercialName,
		String countryCode, String taxType, String taxValue, String segment, String contactPerson,
		String contactEmail, String phone, String deliveryProfile, String paymentCondition, String status,
		String buyerMembershipId, long version) { }
