package com.nexa.api.edge.streaming;

/**
 * Canonical change feed audiences. An event is delivered only to streams whose
 * audience is explicitly stored on the event row.
 */
public enum ChangeEventAudience {
	OWNER,
	SALES,
	WAREHOUSE,
	LOGISTICS,
	BUYER
}
