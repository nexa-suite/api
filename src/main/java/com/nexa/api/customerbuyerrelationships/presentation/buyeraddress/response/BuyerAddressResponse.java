package com.nexa.api.customerbuyerrelationships.presentation.buyeraddress.response;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.BuyerAddressView;

import java.math.BigDecimal;
import java.util.UUID;

public record BuyerAddressResponse(UUID id, String label, String addressType, String line, String reference,
                                   String countryCode, String departmentCode, String provinceCode, String districtCode,
                                   String recipientName, String recipientPhone, String roadType, String streetName,
                                   String streetNumber, String interior, String postalCode,
                                   String receivingInstructions, String receivingHours, BigDecimal latitude,
                                   BigDecimal longitude, String placeId, String source, boolean defaultAddress,
                                   boolean active, long version) {
    public static BuyerAddressResponse from(BuyerAddressView value) {
        var address = value.address();
        return new BuyerAddressResponse(value.id(), value.label(), address.addressType(), address.line(),
                address.reference(), address.countryCode(), address.departmentCode(), address.provinceCode(),
                address.districtCode(), address.recipientName(), address.recipientPhone(), address.roadType(),
                address.streetName(), address.streetNumber(), address.interior(), address.postalCode(),
                address.receivingInstructions(), address.receivingHours(), address.latitude(), address.longitude(),
                address.placeId(), address.source(), value.defaultAddress(), value.active(), value.version());
    }
}
