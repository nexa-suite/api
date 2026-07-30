package com.nexa.api.shared.application.changefeed;

import java.time.Instant;

public record ChangeEventView(long id, String aggregateType, String aggregateId, String eventType,
		String payload, Instant occurredAt) { }
