package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

public record ClientAccountView(String id, String code, String businessName, String commercialName,
		String countryCode, String taxType, String taxValue, String segment, String contactPerson,
		String contactEmail, String phone, String deliveryProfile, String paymentCondition, String status,
		String buyerMembershipId, long version) { }
