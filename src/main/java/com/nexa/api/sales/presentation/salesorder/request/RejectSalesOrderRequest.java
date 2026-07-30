package com.nexa.api.sales.presentation.salesorder.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectSalesOrderRequest(@NotBlank @Size(max = 2000) String reason) { }
