package com.nexa.api.salescommitment.presentation.policy;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record BuyerDeliveryLeadPolicyResponse(
        @Schema(description = "Fixed global minimum number of Monday-Friday business days", example = "5")
        int minimumDeliveryLeadBusinessDays,
        @Schema(description = "First allowed delivery date, calculated from tomorrow in the stated timezone", example = "2026-11-05")
        LocalDate minimumAllowedDeliveryDate,
        @Schema(description = "Timezone used to determine today's date", example = "America/Lima")
        String timezone) { }
