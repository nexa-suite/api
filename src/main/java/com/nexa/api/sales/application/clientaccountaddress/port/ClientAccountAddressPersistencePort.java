package com.nexa.api.sales.application.clientaccountaddress.port;

import com.nexa.api.sales.domain.model.clientaccount.ClientAccountAddress;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Aggregate persistence boundary. The default-address update is one locked transaction in the adapter. */
public interface ClientAccountAddressPersistencePort {
    List<ClientAccountAddress> list(String tenantId, String workspaceId, String clientAccountId);

    Optional<ClientAccountAddress> find(String tenantId, String workspaceId, String clientAccountId, String addressId);

    void insert(ClientAccountAddress address, long nowEpochMillis);

    int update(String tenantId, String workspaceId, String clientAccountId, String addressId,
               String label, String addressType, String line, String reference,
               String departmentCode, String provinceCode, String districtCode, long expectedVersion);

    int setDefault(String tenantId, String workspaceId, String clientAccountId, String addressId,
                   long expectedVersion, long nowEpochMillis);

    default UUID generatedId() { return UUID.randomUUID(); }
}
