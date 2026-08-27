package com.nexa.api.customerbuyerrelationships.domain.model.clientaccount;

import com.nexa.api.customerbuyerrelationships.contract.CustomerAccountId;

import java.util.Objects;

public final class ClientAccount {
	private final CustomerAccountId id;
	private final ClientCode code;
	private final TaxIdentifier taxIdentifier;
	private final BusinessName businessName;
	private final CommercialName commercialName;
	private final ContactEmail contactEmail;
	private final PhoneNumber phoneNumber;
	private final PaymentCondition paymentCondition;
	private ClientAccountStatus status;

	private ClientAccount(CustomerAccountId id, ClientCode code, TaxIdentifier taxIdentifier, BusinessName businessName,
			CommercialName commercialName, ContactEmail contactEmail, PhoneNumber phoneNumber, PaymentCondition paymentCondition) {
		this.id = Objects.requireNonNull(id);
		this.code = Objects.requireNonNull(code);
		this.taxIdentifier = Objects.requireNonNull(taxIdentifier);
		this.businessName = Objects.requireNonNull(businessName);
		this.commercialName = Objects.requireNonNull(commercialName);
		this.contactEmail = Objects.requireNonNull(contactEmail);
		this.phoneNumber = Objects.requireNonNull(phoneNumber);
		this.paymentCondition = Objects.requireNonNull(paymentCondition);
		this.status = ClientAccountStatus.ACTIVE;
	}

	public static ClientAccount create(CustomerAccountId id, ClientCode code, TaxIdentifier taxIdentifier, BusinessName businessName,
			CommercialName commercialName, ContactEmail contactEmail, PhoneNumber phoneNumber, PaymentCondition paymentCondition) {
		return new ClientAccount(id, code, taxIdentifier, businessName, commercialName, contactEmail, phoneNumber, paymentCondition);
	}
	public void activate() { status = ClientAccountStatus.ACTIVE; }
	public void suspend() { status = ClientAccountStatus.SUSPENDED; }
	public CustomerAccountId id() { return id; }
	public ClientCode code() { return code; }
	public TaxIdentifier taxIdentifier() { return taxIdentifier; }
	public BusinessName businessName() { return businessName; }
	public CommercialName commercialName() { return commercialName; }
	public ContactEmail contactEmail() { return contactEmail; }
	public PhoneNumber phoneNumber() { return phoneNumber; }
	public PaymentCondition paymentCondition() { return paymentCondition; }
	public ClientAccountStatus status() { return status; }
}
