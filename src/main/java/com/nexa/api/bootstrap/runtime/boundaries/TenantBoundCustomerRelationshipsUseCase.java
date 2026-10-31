package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.shared.application.error.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerClientAccountRegistrationCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountSalesOwnerAssignment;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountBuyerMembershipMutation;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountRegistrationResult;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountAccessPolicy;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.BuyerAddressView;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.CreateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.UpdateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.port.BuyerAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipsStoreUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory.BuyerMembershipReference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.ActiveSalesMembershipDirectory.SalesMembershipReference;
import com.nexa.api.bootstrap.runtime.boundaries.TenantBusinessTraceabilityBindingsFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Surface;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.CannotCreateTransactionException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Routes existing BC-02 use-case rules through one verified Tenant database transaction. */
public final class TenantBoundCustomerRelationshipsUseCase
        implements ClientAccountUseCase, ClientAccountAddressUseCase, BuyerAddressUseCase, FieldVisitUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantCustomerRelationshipsCompositionFactory compositions;
    private final BuyerMembershipDirectory buyerMemberships;
    private final ActiveSalesMembershipDirectory salesMemberships;
    private final TenantBusinessTraceabilityBindingsFactory traceabilityBindings;

    public TenantBoundCustomerRelationshipsUseCase(TenantBusinessDatabaseRouter router,
            TenantCustomerRelationshipsCompositionFactory compositions,
            BuyerMembershipDirectory buyerMemberships, ActiveSalesMembershipDirectory salesMemberships,
            TenantBusinessTraceabilityBindingsFactory traceabilityBindings) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.compositions = Objects.requireNonNull(compositions,
                "Tenant Customer Relationships composition factory is required");
        this.buyerMemberships = Objects.requireNonNull(buyerMemberships,
                "Central Buyer membership directory is required");
        this.salesMemberships = Objects.requireNonNull(salesMemberships,
                "Central active Sales membership directory is required");
        this.traceabilityBindings = Objects.requireNonNull(traceabilityBindings,
                "Tenant business traceability bindings are required");
    }

    @Override
    public CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status,
                                                        int page, int size) {
        ClientAccountAccessPolicy.requireRead(context);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts()
                .list(context, search, status, page, size));
    }

    @Override
    public ClientAccountView detail(CurrentAccessContext context, String id) {
        ClientAccountAccessPolicy.requireRead(context);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts().detail(context, id));
    }

    @Override
    public ClientAccountView buyerDetail(CurrentAccessContext context) {
        requireScope(context);
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer self-service is only available to the authenticated Buyer membership");
        }
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(Permission.SALES_BUYER_READ);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts().buyerDetail(context));
    }

    @Override
    public ClientAccountRegistrationResult registerOwnBusiness(CurrentAccessContext context,
            BuyerClientAccountRegistrationCommand command) {
        requireScope(context);
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer account registration is only available to the authenticated Buyer membership");
        }
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(Permission.SALES_BUYER_WRITE);
        String membershipId = context.membershipId().toString();
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(), context.workspaceId().toString(), membershipId)
                        .map(List::of).orElseGet(List::of));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts()
                .registerOwnBusiness(context, command));
    }

    @Override
    public List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context) {
        ClientAccountAccessPolicy.requireBuyerCandidateRead(context);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyers(context.tenantId().toString(), context.workspaceId().toString()));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts().buyerMembershipCandidates(context));
    }

    @Override
    public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts().create(context, command));
    }

    @Override
    public ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts().update(context, id, command, version));
    }

    @Override
    public ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        return inTenant(context, List.of(), useCases -> useCases.clientAccounts()
                .changeStatus(context, id, status, version));
    }

    @Override
    public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId,
                                             long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(),
                        context.workspaceId().toString(), membershipId).map(List::of).orElseGet(List::of));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts()
                .associateBuyer(context, id, membershipId, version));
    }

    @Override
    public ClientAccountBuyerMembershipPage buyerMemberships(CurrentAccessContext context, String id,
            int page, int size) {
        ClientAccountAccessPolicy.requireRead(context);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyers(context.tenantId().toString(), context.workspaceId().toString()));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts()
                .buyerMemberships(context, id, page, size));
    }

    @Override
    public ClientAccountBuyerMembershipMutation associateBuyerMembership(CurrentAccessContext context, String id,
            String membershipId, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(),
                        context.workspaceId().toString(), membershipId).map(List::of).orElseGet(List::of));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts()
                .associateBuyerMembership(context, id, membershipId, version));
    }

    @Override
    public ClientAccountBuyerMembershipMutation deactivateBuyerMembership(CurrentAccessContext context, String id,
            String membershipId, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        ClientAccountAccessPolicy.requireManage(context);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(),
                        context.workspaceId().toString(), membershipId).map(List::of).orElseGet(List::of));
        return inTenant(context, snapshot, useCases -> useCases.clientAccounts()
                .deactivateBuyerMembership(context, id, membershipId, version));
    }

    @Override
    public ClientAccountSalesOwnerAssignment assignSalesOwner(CurrentAccessContext context, String id,
            String salesMembershipId, long version) {
        ClientAccountAccessPolicy.requireSalesOwnerAssignment(context);
        List<SalesMembershipReference> snapshot = preflightSalesMembership(context,
                () -> salesMemberships.findActiveSalesMembership(context.tenantId().toString(),
                        context.workspaceId().toString(), salesMembershipId).map(List::of).orElseGet(List::of));
        if (snapshot.isEmpty()) throw new com.nexa.api.shared.application.error.ApiResourceNotFoundException("sales-membership");
        return inTenant(context, List.of(), snapshot, useCases -> useCases.clientAccounts()
                .assignSalesOwner(context, id, salesMembershipId, version));
    }

    @Override
    public List<ClientAccountAddressView> list(CurrentAccessContext context, String clientAccountId) {
        authorizeAddress(context, false);
        return inTenant(context, List.of(), useCases -> useCases.addresses().list(context, clientAccountId));
    }

    @Override
    public ClientAccountAddressView create(CurrentAccessContext context, String clientAccountId,
                                             CreateClientAccountAddressCommand command) {
        authorizeAddress(context, true);
        return inTenant(context, List.of(), useCases -> useCases.addresses().create(context, clientAccountId, command));
    }

    @Override
    public ClientAccountAddressView update(CurrentAccessContext context, String clientAccountId, String addressId,
                                             UpdateClientAccountAddressCommand command, long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, List.of(), useCases -> useCases.addresses()
                .update(context, clientAccountId, addressId, command, expectedVersion));
    }

    @Override
    public ClientAccountAddressView setDefault(CurrentAccessContext context, String clientAccountId, String addressId,
                                                long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, List.of(), useCases -> useCases.addresses()
                .setDefault(context, clientAccountId, addressId, expectedVersion));
    }

    @Override
    public ClientAccountAddressView deactivate(CurrentAccessContext context, String clientAccountId, String addressId,
                                                long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, List.of(), useCases -> useCases.addresses()
                .deactivate(context, clientAccountId, addressId, expectedVersion));
    }

    @Override
    public List<BuyerAddressView> listMine(CurrentAccessContext context) {
        UUID membershipId = authorizeBuyerAddress(context, false);
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, membershipId, false);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses().listMine(context));
    }

    @Override
    public BuyerAddressView createMine(CurrentAccessContext context, CreateBuyerAddressCommand command) {
        UUID membershipId = authorizeBuyerAddress(context, true);
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, membershipId, false);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses().createMine(context, command));
    }

    @Override
    public BuyerAddressView updateMine(CurrentAccessContext context, UUID addressId,
                                       UpdateBuyerAddressCommand command, long expectedVersion) {
        UUID membershipId = authorizeBuyerAddress(context, true);
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, membershipId, false);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses()
                .updateMine(context, addressId, command, expectedVersion));
    }

    @Override
    public BuyerAddressView setDefaultMine(CurrentAccessContext context, UUID addressId, long expectedVersion) {
        UUID membershipId = authorizeBuyerAddress(context, true);
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, membershipId, false);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses()
                .setDefaultMine(context, addressId, expectedVersion));
    }

    @Override
    public BuyerAddressView deactivateMine(CurrentAccessContext context, UUID addressId, long expectedVersion) {
        UUID membershipId = authorizeBuyerAddress(context, true);
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, membershipId, false);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses()
                .deactivateMine(context, addressId, expectedVersion));
    }

    @Override
    public List<BuyerAddressView> listForAccountBuyer(CurrentAccessContext context, String clientAccountId,
                                                       UUID buyerMembershipId) {
        requireScope(context);
        authorizeBuyerAccountAddressRead(context);
        Objects.requireNonNull(buyerMembershipId, "Buyer membership id is required");
        List<BuyerMembershipReference> snapshot = activeBuyerSnapshot(context, buyerMembershipId, true);
        return inTenant(context, snapshot, useCases -> useCases.buyerAddresses()
                .listForAccountBuyer(context, clientAccountId, buyerMembershipId));
    }

    @Override
    public FieldVisitEvidence record(CurrentAccessContext context, String customerId, long version,
                                     String idempotencyKey, FieldVisitUseCase.Command command) {
        authorizeFieldVisit(context, true);
        return inTenant(context, List.of(), useCases -> useCases.fieldVisits()
                .record(context, customerId, version, idempotencyKey, command));
    }

    @Override
    public List<FieldVisitEvidence> listForCustomer(CurrentAccessContext context, String customerId) {
        authorizeFieldVisit(context, false);
        return inTenant(context, List.of(), useCases -> useCases.fieldVisits().listForCustomer(context, customerId));
    }

    private <T> T inTenant(CurrentAccessContext context, List<BuyerMembershipReference> membershipSnapshot,
            java.util.function.Function<TenantCustomerRelationshipsCompositionFactory.Composition, T> operation) {
        return inTenant(context, membershipSnapshot, List.of(), operation);
    }

    private <T> T inTenant(CurrentAccessContext context, List<BuyerMembershipReference> buyerMembershipSnapshot,
            List<SalesMembershipReference> salesMembershipSnapshot,
            java.util.function.Function<TenantCustomerRelationshipsCompositionFactory.Composition, T> operation) {
        requireScope(context);
        try {
            return router.inTransaction(context, jdbc -> operation.apply(Objects.requireNonNull(
                    compositions.bindTo(jdbc, buyerMembershipSnapshot, salesMembershipSnapshot,
                            traceabilityBindings.bindTo(jdbc).commands()),
                    "Tenant Customer Relationships composition factory returned no composition")));
        } catch (TenantBusinessDatabaseUnavailableException | TenantBusinessDatabaseSchemaManifestMismatchException
                 | DataAccessException | CannotCreateTransactionException unavailable) {
            throw new CustomerRelationshipsStoreUnavailableException(unavailable);
        }
    }

    private List<BuyerMembershipReference> preflightBuyerMemberships(CurrentAccessContext context,
            java.util.function.Supplier<List<BuyerMembershipReference>> query) {
        requireScope(context);
        try {
            return List.copyOf(Objects.requireNonNull(query.get(), "Central Buyer membership preflight returned no list"));
        } catch (DataAccessException unavailable) {
            throw new CustomerRelationshipsStoreUnavailableException(unavailable);
        }
    }

    private List<SalesMembershipReference> preflightSalesMembership(CurrentAccessContext context,
            java.util.function.Supplier<List<SalesMembershipReference>> query) {
        requireScope(context);
        try {
            return List.copyOf(Objects.requireNonNull(query.get(),
                    "Central Sales membership preflight returned no list"));
        } catch (DataAccessException unavailable) {
            throw new CustomerRelationshipsStoreUnavailableException(unavailable);
        }
    }

    private static void requireAdministrativePermission(CurrentAccessContext context, Permission permission) {
        requireAdministrativeScope(context);
        context.requirePermission(permission);
    }

    private static void requireAdministrativePermission(CurrentAccessContext context, PermissionKey permission) {
        requireAdministrativeScope(context);
        context.requirePermission(permission);
    }

    private static void requireAdministrativeScope(CurrentAccessContext context) {
        requireScope(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
        }
    }

    private static void authorizeAddress(CurrentAccessContext context, boolean write) {
        requireScope(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            context.requirePermission(write ? Permission.SALES_BUYER_WRITE : Permission.SALES_BUYER_READ);
        } else {
            ClientAccountAccessPolicy.requireAddressAccess(context, write);
        }
    }

    private static UUID authorizeBuyerAddress(CurrentAccessContext context, boolean write) {
        requireScope(context);
        if (!context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Buyer membership is required to manage personal addresses");
        }
        context.requireSurface(Surface.PORTAL);
        context.requirePermission(write ? Permission.SALES_BUYER_WRITE : Permission.SALES_BUYER_READ);
        return context.membershipId().value();
    }

    private static void authorizeBuyerAccountAddressRead(CurrentAccessContext context) {
        context.requireSurface(Surface.PLATFORM);
        if (context.hasRole(MembershipRole.BUSINESS_OPERATIONS_MANAGER)) {
            ClientAccountAccessPolicy.requireRead(context);
            return;
        }
        if (!context.hasRole(MembershipRole.SALES) || context.hasRole(MembershipRole.COMPANY_OWNER)) {
            throw new AccessPolicyViolation("Personal Buyer address audit is restricted to assigned Sales and BOM");
        }
        ClientAccountAccessPolicy.requireRead(context);
    }

    private List<BuyerMembershipReference> activeBuyerSnapshot(CurrentAccessContext context, UUID membershipId,
                                                                 boolean notFoundWhenInactive) {
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(), context.workspaceId().toString(),
                        membershipId.toString()).map(List::of).orElseGet(List::of));
        if (snapshot.isEmpty()) {
            if (notFoundWhenInactive) throw new com.nexa.api.shared.application.error.ApiResourceNotFoundException("client-account");
            throw new AccessPolicyViolation("Active Buyer membership is required");
        }
        return snapshot;
    }

    private static void authorizeFieldVisit(CurrentAccessContext context, boolean write) {
        requireScope(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Internal workforce authority is required");
        }
        context.requirePermission(PermissionKey.CLIENT_READ);
        if (write) context.requirePermission(PermissionKey.CLIENT_MANAGE);
    }

    private static void requireScope(CurrentAccessContext context) {
        Objects.requireNonNull(context, "Verified access context is required");
        UUID tenantId = context.tenantId().value();
        UUID workspaceId = context.workspaceId().value();
        RlsRequestScope.Scope current = RlsRequestScope.current();
        if (current == null || !tenantId.equals(current.tenantId()) || !workspaceId.equals(current.workspaceId())) {
            throw new AccessPolicyViolation("Customer relationships require the matching verified Tenant and Workspace scope");
        }
    }
}
