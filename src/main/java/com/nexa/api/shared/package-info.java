/**
 * Temporarily open only for the cross-context HTTP/security/error composition
 * described by ADR-001. It may not own domain state or inbound business ports.
 */
@org.springframework.modulith.ApplicationModule(id = "shared", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.nexa.api.shared;
