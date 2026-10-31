package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.response;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;

/** Shared business account data visible to the authenticated Buyer. */
public record BuyerClientAccountSelfResponse(String id, String code, String businessName, String commercialName,
        String countryCode, String taxType, String taxValue, String segment,
        String deliveryProfile, String paymentCondition, String status,
        long version, ClientAccountType accountType) { }
