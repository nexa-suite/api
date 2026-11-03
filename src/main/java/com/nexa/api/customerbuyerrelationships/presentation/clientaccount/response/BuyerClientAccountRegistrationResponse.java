package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;
import io.swagger.v3.oas.annotations.media.Schema;

/** Registration result; shared contact details are nullable after another Buyer is linked. */
public record BuyerClientAccountRegistrationResponse(String id, String code, String businessName,
		String commercialName, String countryCode, String taxType, String taxValue, String segment,
		@Schema(nullable = true) String contactPerson,
		@Schema(nullable = true) String contactEmail,
		@Schema(nullable = true) String phone,
		String deliveryProfile, String paymentCondition, String status, String buyerMembershipId, long version,
		ClientAccountType accountType) { }
