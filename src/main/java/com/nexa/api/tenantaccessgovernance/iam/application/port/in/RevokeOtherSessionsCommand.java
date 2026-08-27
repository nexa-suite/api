package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;

public interface RevokeOtherSessionsCommand {
    void revokeOthers(Actor actor);
}
