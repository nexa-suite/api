package com.nexa.api.shared.presentation.changefeed;

import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/change-feed")
@Profile("!test")
@Tag(name = "Change Feed")
@SecurityRequirement(name = "bearerAuth")
public final class ChangeFeedController {
	private static final String ACCESS_CONTEXT_ATTRIBUTE = "com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext";
	private final ChangeFeedStreamService streams;
	public ChangeFeedController(ChangeFeedStreamService streams) { this.streams = streams; }

	@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestAttribute(ACCESS_CONTEXT_ATTRIBUTE) CurrentAccessContext context,
			@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken token)) throw new org.springframework.security.authentication.BadCredentialsException("Bearer token is required");
		return streams.open(context, token.getToken(), lastEventId);
	}
}
