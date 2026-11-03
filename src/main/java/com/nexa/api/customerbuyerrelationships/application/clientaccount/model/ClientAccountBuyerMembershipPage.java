package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

import java.util.List;

public record ClientAccountBuyerMembershipPage(
        List<ClientAccountBuyerMembershipView> items,
        int page,
        int size,
        long total,
        long accountVersion) {
    public ClientAccountBuyerMembershipPage {
        items = List.copyOf(items);
    }
}
