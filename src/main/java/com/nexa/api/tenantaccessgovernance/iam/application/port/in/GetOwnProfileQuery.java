package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;

public interface GetOwnProfileQuery {
    Profile get(Actor actor);
}
