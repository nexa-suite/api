package com.nexa.api.sales.application.clientaccountaddress.port;

import com.nexa.api.sales.application.clientaccountaddress.model.ClientAccountAddressView;
import com.nexa.api.sales.application.clientaccountaddress.model.CreateClientAccountAddressCommand;
import com.nexa.api.sales.application.clientaccountaddress.model.UpdateClientAccountAddressCommand;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;

public interface ClientAccountAddressUseCase {
    List<ClientAccountAddressView> list(CurrentAccessContext context, String clientAccountId);

    ClientAccountAddressView create(CurrentAccessContext context, String clientAccountId,
                                    CreateClientAccountAddressCommand command);

    ClientAccountAddressView update(CurrentAccessContext context, String clientAccountId, String addressId,
                                    UpdateClientAccountAddressCommand command, long expectedVersion);

    ClientAccountAddressView setDefault(CurrentAccessContext context, String clientAccountId,
                                        String addressId, long expectedVersion);

    ClientAccountAddressView deactivate(CurrentAccessContext context, String clientAccountId,
                                        String addressId, long expectedVersion);
}
