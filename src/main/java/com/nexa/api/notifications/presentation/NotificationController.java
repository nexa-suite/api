package com.nexa.api.notifications.presentation;

import com.nexa.api.notifications.application.model.NotificationModels.NotificationPage;
import com.nexa.api.notifications.application.model.NotificationModels.NotificationPreferencesView;
import com.nexa.api.notifications.application.port.in.NotificationUseCase;
import com.nexa.api.notifications.presentation.request.NotificationPreferencesRequest;
import com.nexa.api.tenantmanagement.application.model.CurrentAccessContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
public final class NotificationController {
	private static final String ACCESS_CONTEXT = "com.nexa.api.tenantmanagement.application.model.CurrentAccessContext";
	private final NotificationUseCase notifications;

	public NotificationController(NotificationUseCase notifications) { this.notifications = notifications; }

	@GetMapping({"/api/v1/notifications", "/api/v1/notifications/unread"})
	@Operation(operationId = "listNotifications")
	public NotificationPage inbox(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@RequestParam(defaultValue = "false") boolean unread,
			@RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
		return notifications.inbox(context, unread, limit);
	}

	@GetMapping("/api/v1/notifications/unread-count")
	public UnreadCountResponse unreadCount(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context) {
		return new UnreadCountResponse(notifications.unreadCount(context));
	}

	@PostMapping("/api/v1/notifications/{id}/read")
	public ResponseEntity<Void> markRead(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context, @PathVariable String id) {
		notifications.markRead(context, id, true);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/api/v1/notifications/{id}/read")
	public ResponseEntity<Void> markUnread(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context, @PathVariable String id) {
		notifications.markRead(context, id, false);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/api/v1/notifications/read-all")
	public ResponseEntity<Void> markAllRead(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context) {
		notifications.markAllRead(context);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/api/v1/notifications/preferences")
	public NotificationPreferencesView preferences(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context) {
		return notifications.preferences(context);
	}

	@PatchMapping("/api/v1/notifications/preferences")
	public NotificationPreferencesView updatePreferences(@RequestAttribute(ACCESS_CONTEXT) CurrentAccessContext context,
			@RequestBody NotificationPreferencesRequest request) {
		return notifications.updatePreferences(context, request.toModel());
	}

	public record UnreadCountResponse(long unreadCount) { }
}
