package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;

public interface GetOwnProfileQuery {
    Profile get(Actor actor);
}
