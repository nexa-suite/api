package com.nexa.api.fulfillmentdelivery.application.port;

import com.nexa.api.fulfillmentdelivery.application.model.ExecutionTemperatureModels.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ExecutionTemperaturePort {
    Delivery delivery(Scope scope, boolean driver, boolean lock);
    List<RawLine> lines(Scope scope, UUID fulfillmentId);
    List<Hold> holds(Scope scope);
    Reading replay(Scope scope, String operation, String key, String hash);
    Reading record(Scope scope, Delivery delivery, long expectedVersion, String key, String hash,
            ReadingCommand command, BigDecimal minimum, BigDecimal maximum, boolean excursion, Instant now);
    DispositionResult dispose(Scope scope, Delivery delivery, UUID holdId, long expectedVersion, String key,
            String hash, Disposition disposition, String reason, Instant now);
}
