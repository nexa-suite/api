package com.nexa.api.sales.presentation.clientaccount.response;

public record ClientAccountSummaryResponse(String id, String code, String businessName, String commercialName,
		String segment, String status, String buyerMembershipId, long version) { }
