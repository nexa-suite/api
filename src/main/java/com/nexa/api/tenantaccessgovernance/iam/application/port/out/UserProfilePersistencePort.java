package com.nexa.api.tenantaccessgovernance.iam.application.port.out;

import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.tenantaccessgovernance.iam.application.model.IamSecurityModels.ProfilePatch;

/** Persistence intent for the authenticated user's own profile. */
public interface UserProfilePersistencePort {
    Profile findOwnProfile(Actor actor);
    Profile updateOwn(Actor actor, ProfilePatch patch);
}
