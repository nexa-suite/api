package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import java.util.UUID;

public interface RevokeOwnSessionCommand {
    void revoke(Actor actor, UUID sessionId);
}
