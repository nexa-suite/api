package com.nexa.api.iam.infrastructure.security;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.iam.application.port.out.UserProfilePersistencePort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@Primary
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public final class JdbcUserProfilePersistenceAdapter implements UserProfilePersistencePort {
    private final JdbcIamSecurityPersistence delegate;
    public JdbcUserProfilePersistenceAdapter(JdbcIamSecurityPersistence delegate) { this.delegate = delegate; }
    public Profile findOwnProfile(Actor actor) { return delegate.findOwnProfile(actor); }
    public Profile updateOwn(Actor actor, ProfilePatch patch) { return delegate.updateOwn(actor, patch); }
}
