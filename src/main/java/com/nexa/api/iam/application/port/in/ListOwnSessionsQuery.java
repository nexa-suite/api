package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;

import java.util.List;

public interface ListOwnSessionsQuery {
    List<Session> list(Actor actor);
}
