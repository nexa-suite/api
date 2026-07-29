package com.nexa.api.sales.domain;

import java.util.Objects;

public final class ClientAccount {
	private final ClientAccountId id;
	private final ClientCode code;
	private final TaxIdentifier taxIdentifier;
	private final String businessName;
	private final String commercialName;
	private final String segment;
	private final String contactPerson;
	private final String contactEmail;
	private final String phone;
	private final String deliveryProfile;
	private final String paymentCondition;
	private ClientAccountStatus status;

	private ClientAccount(ClientAccountId id, ClientCode code, TaxIdentifier taxIdentifier, String businessName,
			String commercialName, String segment, String contactPerson, String contactEmail, String phone,
			String deliveryProfile, String paymentCondition) {
		this.id = Objects.requireNonNull(id);
		this.code = Objects.requireNonNull(code);
		this.taxIdentifier = Objects.requireNonNull(taxIdentifier);
		this.businessName = required(businessName, "Business name");
		this.commercialName = required(commercialName, "Commercial name");
		this.segment = required(segment, "Client segment");
		this.contactPerson = required(contactPerson, "Contact person");
		this.contactEmail = required(contactEmail, "Contact email");
		this.phone = required(phone, "Phone number");
		this.deliveryProfile = required(deliveryProfile, "Delivery profile");
		this.paymentCondition = required(paymentCondition, "Payment condition");
		this.status = ClientAccountStatus.ACTIVE;
	}

	public static ClientAccount create(ClientAccountId id, ClientCode code, TaxIdentifier taxIdentifier, String businessName,
			String commercialName, String segment, String contactPerson, String contactEmail, String phone,
			String deliveryProfile, String paymentCondition) {
		return new ClientAccount(id, code, taxIdentifier, businessName, commercialName, segment, contactPerson,
				contactEmail, phone, deliveryProfile, paymentCondition);
	}
	public void activate() { status = ClientAccountStatus.ACTIVE; }
	public void suspend() { status = ClientAccountStatus.SUSPENDED; }
	public ClientAccountId id() { return id; }
	public ClientCode code() { return code; }
	public TaxIdentifier taxIdentifier() { return taxIdentifier; }
	public ClientAccountStatus status() { return status; }
	private static String required(String value, String label) { if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required"); return value.trim(); }
}
