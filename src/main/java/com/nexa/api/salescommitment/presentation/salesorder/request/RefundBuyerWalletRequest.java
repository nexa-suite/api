package com.nexa.api.salescommitment.presentation.salesorder.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RefundBuyerWalletRequest(
        @NotNull @DecimalMin(value = "0.0000", inclusive = false) @Digits(integer = 15, fraction = 4)
        BigDecimal amountPEN,
        @NotBlank @Size(max = 500) String reason) { }
