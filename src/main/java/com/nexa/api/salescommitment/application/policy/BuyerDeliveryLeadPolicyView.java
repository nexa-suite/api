package com.nexa.api.salescommitment.application.policy;

import java.time.LocalDate;

public record BuyerDeliveryLeadPolicyView(int minimumDeliveryLeadBusinessDays,
                                         LocalDate minimumAllowedDeliveryDate,
                                         String timezone) { }
