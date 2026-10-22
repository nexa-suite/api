package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

public record ClientAccountSummaryResponse(String id, String code, String businessName, String commercialName,
		String segment, String status, String buyerMembershipId, long version, ClientAccountType accountType,
		String salesOwnerMembershipId) { }
