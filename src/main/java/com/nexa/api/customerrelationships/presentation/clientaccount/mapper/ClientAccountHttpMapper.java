package com.nexa.api.customerrelationships.presentation.clientaccount.mapper;

import com.nexa.api.customerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerrelationships.presentation.clientaccount.request.CreateClientAccountRequest;
import com.nexa.api.customerrelationships.presentation.clientaccount.request.UpdateClientAccountRequest;
import com.nexa.api.customerrelationships.presentation.clientaccount.response.ClientAccountDetailResponse;
import com.nexa.api.customerrelationships.presentation.clientaccount.response.ClientAccountPageResponse;
import com.nexa.api.customerrelationships.presentation.clientaccount.response.ClientAccountSummaryResponse;
import org.springframework.stereotype.Component;

@Component
public class ClientAccountHttpMapper {
	public ClientAccountView create(CreateClientAccountRequest request) { return new ClientAccountView(null, request.code(), request.businessName(), request.commercialName(), request.countryCode() == null ? "PE" : request.countryCode(), request.taxType() == null ? "RUC" : request.taxType(), request.taxValue(), request.segment(), request.contactPerson(), request.contactEmail(), request.phone(), request.deliveryProfile(), request.paymentCondition(), "ACTIVE", null, 0); }
	public ClientAccountView update(UpdateClientAccountRequest request) { return new ClientAccountView(null, null, request.businessName(), request.commercialName(), null, null, null, null, request.contactPerson(), request.contactEmail(), request.phone(), request.deliveryProfile(), request.paymentCondition(), null, null, 0); }
	public ClientAccountDetailResponse detail(ClientAccountView view) { return new ClientAccountDetailResponse(view.id(), view.code(), view.businessName(), view.commercialName(), view.countryCode(), view.taxType(), view.taxValue(), view.segment(), view.contactPerson(), view.contactEmail(), view.phone(), view.deliveryProfile(), view.paymentCondition(), view.status(), view.buyerMembershipId(), view.version()); }
	public ClientAccountSummaryResponse summary(ClientAccountView view) { return new ClientAccountSummaryResponse(view.id(), view.code(), view.businessName(), view.commercialName(), view.segment(), view.status(), view.buyerMembershipId(), view.version()); }
	public ClientAccountPageResponse page(CustomerAccountPage<ClientAccountView> page) { return new ClientAccountPageResponse(page.items().stream().map(this::summary).toList(), page.page(), page.size(), page.total()); }
}
