package com.nexa.api.sales.presentation.request;

import com.nexa.api.sales.domain.model.address.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Shared HTTP input for a persisted or manual Peru delivery address. */
public record DeliveryAddressRequest(@Size(max = 32) String addressType,
                                     @NotBlank @Size(max = 240) String line,
                                     @Size(max = 500) String reference,
                                     @Size(max = 8) String countryCode,
                                     @NotBlank @Size(max = 40) String departmentCode,
                                     @NotBlank @Size(max = 40) String provinceCode,
                                     @NotBlank @Size(max = 40) String districtCode) {
    public Address toDomain() {
        return new Address(addressType, line, reference, countryCode == null ? "PE" : countryCode,
                departmentCode, provinceCode, districtCode);
    }
}
