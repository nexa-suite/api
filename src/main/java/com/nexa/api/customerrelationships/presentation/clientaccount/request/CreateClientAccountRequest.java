package com.nexa.api.customerrelationships.presentation.clientaccount.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateClientAccountRequest(
		@NotBlank @Size(max = 32) @Pattern(regexp = "[A-Za-z0-9-]{2,32}") String code,
		@NotBlank @Size(max = 160) String businessName,
		@NotBlank @Size(max = 160) String commercialName,
		@Size(max = 2) String countryCode,
		@Size(max = 32) String taxType,
		@NotBlank @Pattern(regexp = "\\d{11}") String taxValue,
		@NotBlank @Size(max = 80) String segment,
		@NotBlank @Size(max = 120) String contactPerson,
		@NotBlank @Email @Size(max = 254) String contactEmail,
		@NotBlank @Pattern(regexp = "[+0-9 ()-]{7,32}") String phone,
		@NotBlank @Size(max = 2000) String deliveryProfile,
		@NotBlank @Size(max = 80) String paymentCondition) { }
