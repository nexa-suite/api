package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

public record ClientAccountView(String id, String code, String businessName, String commercialName,
		String countryCode, String taxType, String taxValue, String segment, String contactPerson,
		String contactEmail, String phone, String deliveryProfile, String paymentCondition, String status,
		String buyerMembershipId, long version, ClientAccountType accountType, String salesOwnerMembershipId) {
	public ClientAccountView(String id, String code, String businessName, String commercialName,
			String countryCode, String taxType, String taxValue, String segment, String contactPerson,
			String contactEmail, String phone, String deliveryProfile, String paymentCondition, String status,
			String buyerMembershipId, long version, ClientAccountType accountType) {
		this(id, code, businessName, commercialName, countryCode, taxType, taxValue, segment, contactPerson,
				contactEmail, phone, deliveryProfile, paymentCondition, status, buyerMembershipId, version,
				accountType, null);
	}
}
