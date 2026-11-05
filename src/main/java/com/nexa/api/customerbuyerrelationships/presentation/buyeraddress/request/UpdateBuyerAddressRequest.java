package com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.request;

import com.nexa.api.customerbuyerrelationships.presentation.contract.DeliveryAddressRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateBuyerAddressRequest(@NotBlank @Size(max = 120) String label,
                                        @Valid @NotNull DeliveryAddressRequest address) { }
