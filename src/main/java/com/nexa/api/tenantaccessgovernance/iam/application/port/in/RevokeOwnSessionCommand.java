package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import java.util.UUID;

public interface RevokeOwnSessionCommand {
    void revoke(Actor actor, UUID sessionId);
}
