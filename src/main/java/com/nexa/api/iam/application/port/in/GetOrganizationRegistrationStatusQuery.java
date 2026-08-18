package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Registration;
import java.util.UUID;

public interface GetOrganizationRegistrationStatusQuery {
    Registration get(UUID registrationId, String statusToken);
}
