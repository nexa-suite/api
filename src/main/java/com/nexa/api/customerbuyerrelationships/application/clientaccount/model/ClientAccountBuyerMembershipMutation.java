package com.nexa.api.customerbuyerrelationships.application.clientaccount.model;

public record ClientAccountBuyerMembershipMutation(
        ClientAccountBuyerMembershipView membership,
        long accountVersion,
        boolean created,
        boolean changed) {
}
