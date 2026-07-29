package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.ValidatedAccessSession;
import com.nexa.api.iam.domain.model.access.ClientSurface;
import com.nexa.api.iam.domain.model.session.SessionId;
import com.nexa.api.iam.domain.model.useraccount.UserAccountId;

public interface ValidateAccessSessionUseCase {
	ValidatedAccessSession validate(SessionId sessionId, UserAccountId userId, ClientSurface surface);
}
