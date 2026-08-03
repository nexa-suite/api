package com.nexa.api.sales.presentation.request;

import com.nexa.api.sales.domain.model.address.Address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Shared HTTP input for a persisted or manual Peru delivery address. */
public record DeliveryAddressRequest(@Size(max = 32) String addressType,
                                     @NotBlank @Size(max = 240) String line,
                                     @Size(max = 500) String reference,
                                     @Size(max = 8) String countryCode,
                                     @NotBlank @Size(max = 40) String departmentCode,
                                     @NotBlank @Size(max = 40) String provinceCode,
                                     @NotBlank @Size(max = 40) String districtCode,
                                     @Size(max = 160) String recipientName,
                                     @Size(max = 48) String recipientPhone,
                                     @Size(max = 32) String roadType,
                                     @Size(max = 180) String streetName,
                                     @Size(max = 32) String streetNumber,
                                     @Size(max = 64) String interior,
                                     @Size(max = 32) String postalCode,
                                     @Size(max = 1000) String receivingInstructions,
                                     @Size(max = 240) String receivingHours,
                                     @Digits(integer = 3, fraction = 7) BigDecimal latitude,
                                     @Digits(integer = 3, fraction = 7) BigDecimal longitude,
                                     @Size(max = 240) String placeId,
                                     @Size(max = 24) String source) {
    public Address toDomain() {
        return new Address(addressType, line, reference, countryCode == null ? "PE" : countryCode,
                departmentCode, provinceCode, districtCode, recipientName, recipientPhone, roadType,
                streetName, streetNumber, interior, postalCode, receivingInstructions, receivingHours,
                latitude, longitude, placeId, source);
    }
}
