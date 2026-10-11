package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.port.ClientAccountUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccount.service.ClientAccountService;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port.ClientAccountAddressUseCase;
import com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitPersistencePort;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.port.FieldVisitUseCase;
import com.nexa.api.customerbuyerrelationships.application.fieldvisit.service.FieldVisitService;
import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountAddressPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantClientAccountPersistenceFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerAccountQueryFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantCustomerRelationshipsCompositionFactory;
import com.nexa.api.customerbuyerrelationships.tenantdatabase.TenantFieldVisitPersistenceFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.publicapi.BuyerMembershipDirectory.BuyerMembershipReference;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Composes BC-02 application services without taking ownership of routing or Tenant transactions. */
@Component
@Profile("!test")
public final class JdbcTenantCustomerRelationshipsCompositionFactory
        implements TenantCustomerRelationshipsCompositionFactory {
    private final TenantClientAccountPersistenceFactory accountPersistence;
    private final TenantCustomerAccountQueryFactory accountQueries;
    private final TenantClientAccountAddressPersistenceFactory addressPersistence;
    private final TenantFieldVisitPersistenceFactory fieldVisitPersistence;
    private final Clock clock;
    private final ObjectMapper mapper;

    public JdbcTenantCustomerRelationshipsCompositionFactory(
            TenantClientAccountPersistenceFactory accountPersistence,
            TenantCustomerAccountQueryFactory accountQueries,
            TenantClientAccountAddressPersistenceFactory addressPersistence,
            TenantFieldVisitPersistenceFactory fieldVisitPersistence,
            Clock clock, ObjectMapper mapper) {
        this.accountPersistence = Objects.requireNonNull(accountPersistence);
        this.accountQueries = Objects.requireNonNull(accountQueries);
        this.addressPersistence = Objects.requireNonNull(addressPersistence);
        this.fieldVisitPersistence = Objects.requireNonNull(fieldVisitPersistence);
        this.clock = Objects.requireNonNull(clock);
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Composition bindTo(JdbcTemplate tenantJdbc, List<BuyerMembershipReference> buyerMembershipSnapshot) {
        JdbcTemplate jdbc = Objects.requireNonNull(tenantJdbc, "Tenant JDBC session is required");
        List<BuyerMembershipReference> snapshot = List.copyOf(Objects.requireNonNull(
                buyerMembershipSnapshot, "Buyer membership snapshot is required"));
        ClientAccountPersistencePort tenantPersistence = Objects.requireNonNull(accountPersistence.bindTo(jdbc),
                "Tenant Customer Account persistence factory returned no port");
        BuyerMembershipDirectory membershipDirectory = membershipSnapshot(snapshot);
        ClientAccountUseCase clientAccounts = new ClientAccountService(tenantPersistence, membershipDirectory);
        CustomerAccountQuery tenantAccountQuery = Objects.requireNonNull(accountQueries.bindTo(jdbc),
                "Tenant Customer Account query factory returned no query");
        ClientAccountAddressUseCase addresses = new ClientAccountAddressService(
                Objects.requireNonNull(addressPersistence.bindTo(jdbc),
                        "Tenant Customer Account address factory returned no port"), tenantAccountQuery);
        FieldVisitPersistencePort tenantFieldVisits = Objects.requireNonNull(fieldVisitPersistence.bindTo(jdbc),
                "Tenant field-visit persistence factory returned no port");
        FieldVisitUseCase fieldVisits = new FieldVisitService(clientAccounts, tenantFieldVisits, clock, mapper);
        return new Composition(clientAccounts, addresses, fieldVisits);
    }

    private static BuyerMembershipDirectory membershipSnapshot(List<BuyerMembershipReference> members) {
        List<BuyerMembershipReference> snapshot = List.copyOf(members);
        return new BuyerMembershipDirectory() {
            @Override
            public List<BuyerMembershipReference> findActiveBuyers(String tenantId, String workspaceId) {
                return snapshot;
            }

            @Override
            public Optional<BuyerMembershipReference> findActiveBuyer(
                    String tenantId, String workspaceId, String membershipId) {
                return snapshot.stream().filter(member -> member.id().equals(membershipId)).findFirst();
            }
        };
    }
}
