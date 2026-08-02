package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import java.util.List;
import java.util.UUID;

/** Own-session persistence intent. All operations are user-scoped. */
public interface RefreshSessionPersistencePort {
    List<Session> findOwnSessions(Actor actor);
    void revoke(Actor actor, UUID sessionId);
    void revokeAllExceptCurrent(Actor actor);
}
