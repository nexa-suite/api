package com.nexa.api.iam.application.service;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;
import com.nexa.api.iam.application.model.IamSecurityModels.Profile;
import com.nexa.api.iam.application.model.IamSecurityModels.ProfilePatch;
import com.nexa.api.iam.application.port.in.GetOwnProfileQuery;
import com.nexa.api.iam.application.port.in.UpdateOwnProfileCommand;
import com.nexa.api.iam.application.port.out.IamSecurityRepository;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class OwnProfileService implements GetOwnProfileQuery, UpdateOwnProfileCommand {
    private final IamSecurityRepository repository;

    public OwnProfileService(IamSecurityRepository repository) { this.repository = repository; }

    @Override
    @Transactional(readOnly = true)
    public Profile get(Actor actor) { return repository.profile(actor); }

    @Override
    @Transactional
    public Profile update(Actor actor, ProfilePatch patch) { return repository.updateProfile(actor, patch); }
}
