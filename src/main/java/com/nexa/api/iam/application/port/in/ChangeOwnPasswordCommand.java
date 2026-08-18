package com.nexa.api.iam.application.port.in;

import com.nexa.api.iam.application.model.IamSecurityModels.Actor;

public interface ChangeOwnPasswordCommand {
    void change(Actor actor, String currentPassword, String newPassword);
}
