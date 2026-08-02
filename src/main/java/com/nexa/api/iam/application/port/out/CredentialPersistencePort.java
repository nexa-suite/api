package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;

/** Persistence intent for credential changes; policy and orchestration stay in Application. */
public interface CredentialPersistencePort {
    void changeOwnPassword(Actor actor, String currentPassword, String newPassword);
}
