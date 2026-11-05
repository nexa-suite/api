package com.nexa.api.customerbuyerrelationships.application.clientaccount.port;

import com.nexa.api.customerbuyerrelationships.application.clientaccount.model.ClientAccountView;

/** Result distinguishes a newly created Buyer account from an exact safe retry. */
public record ClientAccountRegistrationResult(ClientAccountView account, boolean created) { }
