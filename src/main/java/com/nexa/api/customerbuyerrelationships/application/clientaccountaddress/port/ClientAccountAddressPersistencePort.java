package com.nexa.api.customerbuyerrelationships.application.clientaccountaddress.port;

import com.nexa.api.customerbuyerrelationships.domain.model.clientaccount.ClientAccountAddress;
import com.nexa.api.customerbuyerrelationships.contract.Address;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Aggregate persistence boundary. The default-address update is one locked transaction in the adapter. */
public interface ClientAccountAddressPersistencePort {
    List<ClientAccountAddress> list(String tenantId, String workspaceId, String clientAccountId);

    Optional<ClientAccountAddress> find(String tenantId, String workspaceId, String clientAccountId, String addressId);

    default Optional<ClientAccountAddress> findActiveForUpdate(
            String tenantId, String workspaceId, String clientAccountId, String addressId) {
        return find(tenantId, workspaceId, clientAccountId, addressId).filter(ClientAccountAddress::active);
    }

    void insert(ClientAccountAddress address, long nowEpochMillis);

    int update(String tenantId, String workspaceId, String clientAccountId, String addressId,
               String label, String addressType, String line, String reference,
               String departmentCode, String provinceCode, String districtCode, long expectedVersion);

    default int update(String tenantId, String workspaceId, String clientAccountId, String addressId,
                       String label, Address address, long expectedVersion) {
        return update(tenantId, workspaceId, clientAccountId, addressId, label, address.addressType(), address.line(),
                address.reference(), address.departmentCode(), address.provinceCode(), address.districtCode(), expectedVersion);
    }

    int setDefault(String tenantId, String workspaceId, String clientAccountId, String addressId,
                   long expectedVersion, long nowEpochMillis);

    default int deactivate(String tenantId, String workspaceId, String clientAccountId, String addressId,
                           long expectedVersion, long nowEpochMillis) { return 0; }

    default UUID generatedId() { return UUID.randomUUID(); }
}
