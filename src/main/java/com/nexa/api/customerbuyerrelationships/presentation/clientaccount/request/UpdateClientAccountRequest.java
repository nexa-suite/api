package com.nexa.api.customerbuyerrelationships.presentation.clientaccount.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateClientAccountRequest(
		@Size(max = 160) String businessName,
		@Size(max = 160) String commercialName,
		@Size(max = 120) String contactPerson,
		@Email @Size(max = 254) String contactEmail,
		@Pattern(regexp = "[+0-9 ()-]{7,32}") String phone,
		@Size(max = 2000) String deliveryProfile,
		@Size(max = 80) String paymentCondition) { }
