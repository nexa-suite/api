package com.nexa.api.sales.application;

import com.nexa.api.sales.SalesTestFixtures;
import com.nexa.api.customerrelationships.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.customerrelationships.application.clientaccountaddress.port.ClientAccountAddressPersistencePort;
import com.nexa.api.customerrelationships.application.clientaccountaddress.service.ClientAccountAddressService;
import com.nexa.api.customerrelationships.application.exception.CustomerRelationshipConflictException;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.customerrelationships.application.publicapi.CustomerAccountReference;
import com.nexa.api.customerrelationships.domain.model.clientaccount.ClientAccountAddress;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAccountAddressTests {
    @Test
    void addressCannotBeReadOutsideTenantWorkspaceAndAccountScope() {
        ClientAccountAddress value = SalesTestFixtures.savedAddress(true, 4);

        assertThat(value.belongsTo(SalesTestFixtures.TENANT, SalesTestFixtures.WORKSPACE, SalesTestFixtures.ACCOUNT)).isTrue();
        assertThat(value.belongsTo(java.util.UUID.randomUUID(), SalesTestFixtures.WORKSPACE, SalesTestFixtures.ACCOUNT)).isFalse();
        assertThat(value.belongsTo(SalesTestFixtures.TENANT, SalesTestFixtures.WORKSPACE, "another-account")).isFalse();
        assertThat(value.withDefault(false, 5).defaultAddress()).isFalse();
        assertThat(value.defaultAddress()).isTrue();
    }

    @Test
    void staleDefaultAddressVersionIsRejectedAsConcurrencyConflict() {
        ClientAccountAddress value = SalesTestFixtures.savedAddress(false, 7);
        CustomerAccountQuery accounts = accountPort();
        ClientAccountAddressPersistencePort persistence = new ClientAccountAddressPersistencePort() {
            @Override public List<ClientAccountAddress> list(String tenant, String workspace, String account) { return List.of(value); }
            @Override public Optional<ClientAccountAddress> find(String tenant, String workspace, String account, String id) { return Optional.of(value); }
            @Override public void insert(ClientAccountAddress address, long now) { }
            @Override public int update(String tenant, String workspace, String account, String id, String label, String type,
                                        String line, String reference, String department, String province, String district, long expected) { return 0; }
            @Override public int setDefault(String tenant, String workspace, String account, String id, long expected, long now) {
                return expected == value.version() ? 1 : 0;
            }
        };
        var service = new ClientAccountAddressService(persistence, accounts);
        assertThatThrownBy(() -> service.setDefault(SalesTestFixtures.salesContext(), SalesTestFixtures.ACCOUNT,
                value.id().toString(), value.version() - 1)).isInstanceOf(CustomerRelationshipConflictException.class);
    }

    @Test
    void defaultCreationSurfacesAConcurrentDefaultConflict() {
        CustomerAccountQuery accounts = accountPort();
        ClientAccountAddressPersistencePort persistence = new ClientAccountAddressPersistencePort() {
            @Override public List<ClientAccountAddress> list(String tenant, String workspace, String account) { return List.of(); }
            @Override public Optional<ClientAccountAddress> find(String tenant, String workspace, String account, String id) { return Optional.empty(); }
            @Override public void insert(ClientAccountAddress address, long now) { }
            @Override public int update(String tenant, String workspace, String account, String id, String label, String type,
                                        String line, String reference, String department, String province, String district, long expected) { return 0; }
            @Override public int setDefault(String tenant, String workspace, String account, String id, long expected, long now) { return 0; }
        };
        var service = new ClientAccountAddressService(persistence, accounts);
        assertThatThrownBy(() -> service.create(SalesTestFixtures.salesContext(), SalesTestFixtures.ACCOUNT,
                new CreateClientAccountAddressCommand("New", SalesTestFixtures.address(), true)))
                .isInstanceOf(CustomerRelationshipConflictException.class);
    }

    @Test
    void inactiveAddressIsNotEligibleForDefaultAndIsReportedAsMissing() {
        ClientAccountAddress inactive = SalesTestFixtures.savedAddress(false, 7).deactivate(8);
        AtomicBoolean mutated = new AtomicBoolean();
        ClientAccountAddressPersistencePort persistence = new ClientAccountAddressPersistencePort() {
            @Override public List<ClientAccountAddress> list(String tenant, String workspace, String account) { return List.of(); }
            @Override public Optional<ClientAccountAddress> find(String tenant, String workspace, String account, String id) {
                return Optional.of(inactive);
            }
            @Override public void insert(ClientAccountAddress address, long now) { }
            @Override public int update(String tenant, String workspace, String account, String id, String label, String type,
                                        String line, String reference, String department, String province, String district, long expected) { return 0; }
            @Override public int setDefault(String tenant, String workspace, String account, String id, long expected, long now) {
                mutated.set(true);
                return 0;
            }
        };
        var service = new ClientAccountAddressService(persistence, accountPort());

        assertThatThrownBy(() -> service.setDefault(SalesTestFixtures.salesContext(), SalesTestFixtures.ACCOUNT,
                inactive.id().toString(), inactive.version()))
                .isInstanceOf(com.nexa.api.shared.application.error.ApiResourceNotFoundException.class);
        assertThat(mutated).isFalse();
    }

    private static CustomerAccountQuery accountPort() {
        return new CustomerAccountQuery() {
            @Override public Optional<CustomerAccountReference> findReference(String tenant, String workspace, String account) {
                return Optional.of(new CustomerAccountReference(account, "ACTIVE"));
            }
            @Override public Optional<CustomerAccountReference> findBuyerReference(String tenant, String workspace, String membership) {
                return Optional.empty();
            }
        };
    }
}
