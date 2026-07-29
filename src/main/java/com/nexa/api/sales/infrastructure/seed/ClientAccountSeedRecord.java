package com.nexa.api.sales.infrastructure.seed;

import java.math.BigDecimal;

public record ClientAccountSeedRecord(String code, String businessName, String commercialName, String ruc,
		String segment, String contact, String contactEmail, String phone, String paymentCondition,
		BigDecimal monthlyCreditLimit, BigDecimal monthlyCreditUsed, String monthlyCreditStatus,
		String deliveryPreference, String address, String district, String province, String deliveryReference,
		boolean portalAccess, String sellerWorkspaceEmail, String status) { }
