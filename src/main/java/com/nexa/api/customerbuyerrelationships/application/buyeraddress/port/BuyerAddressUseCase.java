package com.nexa.api.customerbuyerrelationships.application.buyeraddress.port;

import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.BuyerAddressView;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.CreateBuyerAddressCommand;
import com.nexa.api.customerbuyerrelationships.application.buyeraddress.model.UpdateBuyerAddressCommand;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;

import java.util.List;
import java.util.UUID;

@org.springframework.modulith.NamedInterface("buyer-address-usecases")
public interface BuyerAddressUseCase {
    List<BuyerAddressView> listMine(CurrentAccessContext context);

    BuyerAddressView createMine(CurrentAccessContext context, CreateBuyerAddressCommand command);

    BuyerAddressView updateMine(CurrentAccessContext context, UUID addressId, UpdateBuyerAddressCommand command,
                                long expectedVersion);

    BuyerAddressView setDefaultMine(CurrentAccessContext context, UUID addressId, long expectedVersion);

    BuyerAddressView deactivateMine(CurrentAccessContext context, UUID addressId, long expectedVersion);

    List<BuyerAddressView> listForAccountBuyer(CurrentAccessContext context, String clientAccountId,
                                               UUID buyerMembershipId);
}
