package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;

public interface RevokeOtherSessionsCommand {
    void revokeOthers(Actor actor);
}
