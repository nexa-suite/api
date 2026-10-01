package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.CustomerInstructionModels.*;
import java.util.UUID;

public interface CustomerInstructionPort {
    Snapshot read(UUID tenant, UUID workspace, UUID order);
    Snapshot publish(Publish command);
}
