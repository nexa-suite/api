package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterBuyerClientAccountRequest(
		@NotNull ClientAccountType accountType,
		@Size(max = 160) String businessName,
		@Size(max = 160) String commercialName,
		@Size(max = 2) String countryCode,
		@Size(max = 32) String taxType,
		@Size(max = 64) String taxValue,
		@NotBlank @Pattern(regexp = "[+0-9 ()-]{7,32}") String phone,
		@Size(max = 2000) String deliveryProfile) {
}
