package com.nexa.api.sales.application.clientaccount.service;

import com.nexa.api.sales.application.clientaccount.model.ClientAccountView;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.sales.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.sales.application.exception.SalesConcurrencyConflictException;
import com.nexa.api.sales.application.exception.SalesResourceNotFoundException;
import com.nexa.api.sales.application.model.SalesPage;
import com.nexa.api.sales.domain.model.clientaccount.*;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantmanagement.domain.model.access.Permission;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public class ClientAccountService implements ClientAccountUseCase {
	private final ClientAccountPersistencePort persistence;
	public ClientAccountService(ClientAccountPersistencePort persistence) { this.persistence = persistence; }

	@Override public SalesPage<ClientAccountView> list(CurrentAccessContext context, String search, String status, int page, int size) {
		internal(context, Permission.SALES_READ); return persistence.list(scope(context), workspace(context), search, status, page, size);
	}
	@Override public ClientAccountView detail(CurrentAccessContext context, String id) {
		internal(context, Permission.SALES_READ); return persistence.find(scope(context), workspace(context), id).orElseThrow(() -> new SalesResourceNotFoundException("client-account"));
	}
	@Override @Transactional public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) {
		internal(context, Permission.SALES_WRITE); validateDomain(command);
		UUID id = UUID.randomUUID(); persistence.insert(command, scope(context), workspace(context), id, now());
		return detail(context, id.toString());
	}
	@Override @Transactional public ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version) {
		internal(context, Permission.SALES_WRITE);
		int changed = persistence.update(scope(context), workspace(context), id, command.businessName(), command.commercialName(), command.contactPerson(), command.contactEmail(), command.phone(), command.deliveryProfile(), command.paymentCondition(), version);
		if (changed == 0) throw new SalesConcurrencyConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version) {
		internal(context, Permission.SALES_WRITE);
		if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) throw new SalesResourceNotFoundException("client-account-status");
		if (persistence.updateStatus(scope(context), workspace(context), id, status, version) == 0) throw new SalesConcurrencyConflictException(); return detail(context, id);
	}
	@Override @Transactional public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId, long version) {
		internal(context, Permission.SALES_WRITE);
		ClientAccountView account = detail(context, id);
		if (account.version() != version) throw new SalesConcurrencyConflictException();
		if (!persistence.isAvailableBuyerMembership(scope(context), workspace(context), membershipId)) throw new SalesResourceNotFoundException("buyer-membership");
		if (persistence.associateBuyer(scope(context), workspace(context), account.id(), membershipId, UUID.randomUUID(), now(), version) == 0) throw new SalesConcurrencyConflictException();
		return detail(context, id);
	}

	private static void validateDomain(ClientAccountView command) {
		new ClientCode(command.code()); new TaxIdentifier(command.countryCode(), command.taxType(), command.taxValue());
		new BusinessName(command.businessName()); new CommercialName(command.commercialName()); new ContactEmail(command.contactEmail());
		new PhoneNumber(command.phone()); new PaymentCondition(command.paymentCondition());
	}
	private static void internal(CurrentAccessContext context, Permission permission) { if (context.role().name().equals("BUYER")) throw new com.nexa.api.tenantmanagement.domain.model.access.AccessPolicyViolation("Administrative sales access is not available to buyers"); context.requirePermission(permission); }
	private static String scope(CurrentAccessContext context) { return context.tenantId().toString(); }
	private static String workspace(CurrentAccessContext context) { return context.workspaceId().toString(); }
	private static long now() { return System.currentTimeMillis(); }
}
