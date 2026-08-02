package com.nexa.api.iam.application.port.out;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Activation;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import com.nexa.api.iam.application.model.IamSecurityModels.RegistrationRequest;
import com.nexa.api.iam.application.model.IamSecurityModels.Session;
import com.nexa.api.iam.application.model.SystemOperatorContext;

import java.util.List;
import java.util.UUID;

/** IAM persistence boundary. SQL and row mapping stay behind this port. */
public interface IamSecurityRepository {
    Profile profile(Actor actor);
    Profile updateProfile(Actor actor, ProfilePatch patch);
    void changePassword(Actor actor, String currentPassword, String newPassword);
    List<Session> sessions(Actor actor);
    void revokeSession(Actor actor, UUID sessionId);
    void revokeOtherSessions(Actor actor);
    String requestPasswordReset(String email, String surface, String clientAddress, String correlationId, String traceId);
    void resetPassword(String token, String newPassword, String correlationId, String traceId);
    Registration submitRegistration(RegistrationRequest request, String correlationId, String traceId);
    Registration registration(UUID registrationId, String statusToken);
    Activation activate(UUID registrationId, SystemOperatorContext operator, String correlationId, String traceId);
    Registration reject(UUID registrationId, SystemOperatorContext operator, String reason, String correlationId, String traceId);
}
