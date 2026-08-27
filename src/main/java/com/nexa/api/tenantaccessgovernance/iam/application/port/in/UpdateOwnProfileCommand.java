package com.nexa.api.tenantaccessgovernance.iam.application.port.in;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.ProfilePatch;

public interface UpdateOwnProfileCommand {
    Profile update(Actor actor, ProfilePatch patch);
}
