package com.nexa.api.sales.presentation.clientaccount.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AssociateBuyerMembershipRequest(@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String membershipId) { }
