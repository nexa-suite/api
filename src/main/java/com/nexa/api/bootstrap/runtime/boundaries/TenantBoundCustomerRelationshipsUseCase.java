package com.nexa.api.bootstrap.runtime.boundaries;

import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseRouter;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseSchemaManifestMismatchException;
import com.nexa.api.bootstrap.runtime.database.tenant.TenantBusinessDatabaseUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.BuyerMembershipCandidate;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.CustomerAccountPage;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerbuyerrelationships.application.exception.CustomerRelationshipsStoreUnavailableException;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.model.FieldVisitEvidence;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.service.FieldVisitService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountAddressPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantFieldVisitPersistenceFactory;
import com.nexa.api.shared.context.RlsRequestScope;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory.BuyerMembershipReference;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.AccessPolicyViolation;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.Permission;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.PermissionKey;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/** Routes existing BC-02 use-case rules through one verified Tenant database transaction. */
public final class TenantBoundCustomerRelationshipsUseCase
        implements ClientAccountUseCase, ClientAccountAddressUseCase, FieldVisitUseCase {
    private final TenantBusinessDatabaseRouter router;
    private final TenantClientAccountPersistenceFactory accountPersistence;
    private final TenantCustomerAccountQueryFactory accountQueries;
    private final TenantClientAccountAddressPersistenceFactory addressPersistence;
    private final TenantFieldVisitPersistenceFactory fieldVisitPersistence;
    private final BuyerMembershipDirectory buyerMemberships;
    private final Clock clock;
    private final ObjectMapper mapper;

    public TenantBoundCustomerRelationshipsUseCase(TenantBusinessDatabaseRouter router,
            TenantClientAccountPersistenceFactory accountPersistence,
            TenantCustomerAccountQueryFactory accountQueries,
            TenantClientAccountAddressPersistenceFactory addressPersistence,
            TenantFieldVisitPersistenceFactory fieldVisitPersistence,
            BuyerMembershipDirectory buyerMemberships, Clock clock, ObjectMapper mapper) {
        this.router = Objects.requireNonNull(router, "Tenant business database router is required");
        this.accountPersistence = Objects.requireNonNull(accountPersistence,
                "Tenant Customer Account persistence factory is required");
        this.accountQueries = Objects.requireNonNull(accountQueries,
                "Tenant Customer Account query factory is required");
        this.addressPersistence = Objects.requireNonNull(addressPersistence,
                "Tenant Customer Account address persistence factory is required");
        this.fieldVisitPersistence = Objects.requireNonNull(fieldVisitPersistence,
                "Tenant field-visit persistence factory is required");
        this.buyerMemberships = Objects.requireNonNull(buyerMemberships,
                "Central Buyer membership directory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.mapper = Objects.requireNonNull(mapper, "Object mapper is required");
    }

    @Override
    public CustomerAccountPage<ClientAccountView> list(CurrentAccessContext context, String search, String status,
                                                        int page, int size) {
        requireAdministrativePermission(context, Permission.SALES_READ);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).list(context, search, status, page, size));
    }

    @Override
    public ClientAccountView detail(CurrentAccessContext context, String id) {
        requireAdministrativePermission(context, Permission.SALES_READ);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).detail(context, id));
    }

    @Override
    public ClientAccountView buyerDetail(CurrentAccessContext context) {
        requireScope(context);
        context.requirePermission(Permission.SALES_BUYER_READ);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).buyerDetail(context));
    }

    @Override
    public List<BuyerMembershipCandidate> buyerMembershipCandidates(CurrentAccessContext context) {
        requireAdministrativePermission(context, Permission.SALES_READ);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyers(context.tenantId().toString(), context.workspaceId().toString()));
        return inTenant(context, jdbc -> accountService(jdbc, snapshot)
                .buyerMembershipCandidates(context));
    }

    @Override
    public ClientAccountView create(CurrentAccessContext context, ClientAccountView command) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).create(context, command));
    }

    @Override
    public ClientAccountView update(CurrentAccessContext context, String id, ClientAccountView command, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).update(context, id, command, version));
    }

    @Override
    public ClientAccountView changeStatus(CurrentAccessContext context, String id, String status, long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        return inTenant(context, jdbc -> accountService(jdbc, List.of()).changeStatus(context, id, status, version));
    }

    @Override
    public ClientAccountView associateBuyer(CurrentAccessContext context, String id, String membershipId,
                                             long version) {
        requireAdministrativePermission(context, Permission.SALES_WRITE);
        List<BuyerMembershipReference> snapshot = preflightBuyerMemberships(context,
                () -> buyerMemberships.findActiveBuyer(context.tenantId().toString(),
                        context.workspaceId().toString(), membershipId).map(List::of).orElseGet(List::of));
        return inTenant(context, jdbc -> accountService(jdbc, snapshot)
                .associateBuyer(context, id, membershipId, version));
    }

    @Override
    public List<ClientAccountAddressView> list(CurrentAccessContext context, String clientAccountId) {
        authorizeAddress(context, false);
        return inTenant(context, jdbc -> addressService(jdbc).list(context, clientAccountId));
    }

    @Override
    public ClientAccountAddressView create(CurrentAccessContext context, String clientAccountId,
                                             CreateClientAccountAddressCommand command) {
        authorizeAddress(context, true);
        return inTenant(context, jdbc -> addressService(jdbc).create(context, clientAccountId, command));
    }

    @Override
    public ClientAccountAddressView update(CurrentAccessContext context, String clientAccountId, String addressId,
                                             UpdateClientAccountAddressCommand command, long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, jdbc -> addressService(jdbc)
                .update(context, clientAccountId, addressId, command, expectedVersion));
    }

    @Override
    public ClientAccountAddressView setDefault(CurrentAccessContext context, String clientAccountId, String addressId,
                                                long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, jdbc -> addressService(jdbc)
                .setDefault(context, clientAccountId, addressId, expectedVersion));
    }

    @Override
    public ClientAccountAddressView deactivate(CurrentAccessContext context, String clientAccountId, String addressId,
                                                long expectedVersion) {
        authorizeAddress(context, true);
        return inTenant(context, jdbc -> addressService(jdbc)
                .deactivate(context, clientAccountId, addressId, expectedVersion));
    }

    @Override
    public FieldVisitEvidence record(CurrentAccessContext context, String customerId, long version,
                                     String idempotencyKey, FieldVisitUseCase.Command command) {
        authorizeFieldVisit(context, true);
        return inTenant(context, jdbc -> new FieldVisitService(accountService(jdbc, List.of()),
                fieldVisitPersistence.bindTo(jdbc), clock, mapper)
                .record(context, customerId, version, idempotencyKey, command));
    }

    @Override
    public List<FieldVisitEvidence> listForCustomer(CurrentAccessContext context, String customerId) {
        authorizeFieldVisit(context, false);
        return inTenant(context, jdbc -> new FieldVisitService(accountService(jdbc, List.of()),
                fieldVisitPersistence.bindTo(jdbc), clock, mapper).listForCustomer(context, customerId));
    }

    private ClientAccountService accountService(JdbcTemplate jdbc, List<BuyerMembershipReference> snapshot) {
        ClientAccountPersistencePort tenantPersistence = Objects.requireNonNull(accountPersistence.bindTo(jdbc),
                "Tenant Customer Account persistence factory returned no port");
        return new ClientAccountService(tenantPersistence, membershipSnapshot(snapshot));
    }

    private ClientAccountAddressService addressService(JdbcTemplate jdbc) {
        CustomerAccountQuery tenantAccounts = Objects.requireNonNull(accountQueries.bindTo(jdbc),
                "Tenant Customer Account query factory returned no query");
        return new ClientAccountAddressService(Objects.requireNonNull(addressPersistence.bindTo(jdbc),
                "Tenant Customer Account address factory returned no port"), tenantAccounts);
    }

    private <T> T inTenant(CurrentAccessContext context, Function<JdbcTemplate, T> operation) {
        requireScope(context);
        try {
            return router.inTransaction(context, operation::apply);
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

    private static BuyerMembershipDirectory membershipSnapshot(List<BuyerMembershipReference> members) {
        List<BuyerMembershipReference> snapshot = List.copyOf(members);
        return new BuyerMembershipDirectory() {
            @Override
            public List<BuyerMembershipReference> findActiveBuyers(String tenantId, String workspaceId) {
                return snapshot;
            }

            @Override
            public java.util.Optional<BuyerMembershipReference> findActiveBuyer(
                    String tenantId, String workspaceId, String membershipId) {
                return snapshot.stream().filter(member -> member.id().equals(membershipId)).findFirst();
            }
        };
    }

    private static void requireAdministrativePermission(CurrentAccessContext context, Permission permission) {
        requireScope(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            throw new AccessPolicyViolation("Administrative sales access is not available to buyers");
        }
        context.requirePermission(permission);
    }

    private static void authorizeAddress(CurrentAccessContext context, boolean write) {
        requireScope(context);
        if (context.hasRole(MembershipRole.BUYER)) {
            context.requirePermission(write ? Permission.SALES_BUYER_WRITE : Permission.SALES_BUYER_READ);
        } else {
            context.requirePermission(write ? Permission.SALES_WRITE : Permission.SALES_READ);
        }
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
