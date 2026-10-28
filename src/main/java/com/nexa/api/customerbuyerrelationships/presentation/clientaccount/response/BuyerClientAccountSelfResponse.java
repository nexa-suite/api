package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Account details for the authenticated Buyer without unverified account contact data. */
public record BuyerClientAccountSelfResponse(String id, String code, String businessName, String commercialName,
        String countryCode, String taxType, String taxValue, String segment,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contactPerson,
        @JsonInclude(JsonInclude.Include.NON_NULL) String contactEmail,
        @JsonInclude(JsonInclude.Include.NON_NULL) String phone, String deliveryProfile, String paymentCondition, String status,
        String buyerMembershipId, long version) { }
