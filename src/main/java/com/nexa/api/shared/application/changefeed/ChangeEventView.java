package com.nexa.api.shared.application.changefeed;

import java.time.Instant;

public record ChangeEventView(long sequence, String eventId, String aggregateType, String aggregateId,
		String eventType, Long aggregateVersion, String publicStatus, Instant occurredAt) {
	public String dataJson() {
		String version = aggregateVersion == null ? "null" : Long.toString(aggregateVersion);
		String status = publicStatus == null ? "null" : "\"" + escape(publicStatus) + "\"";
		return "{\"eventId\":\"" + eventId + "\",\"aggregateType\":\"" + escape(aggregateType)
				+ "\",\"aggregateId\":\"" + aggregateId + "\",\"aggregateVersion\":" + version
				+ ",\"status\":" + status + ",\"occurredAt\":\"" + occurredAt + "\"}";
	}
	private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
