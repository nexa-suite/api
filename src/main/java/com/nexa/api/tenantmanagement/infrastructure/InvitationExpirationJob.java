package com.nexa.api.tenantmanagement.infrastructure;

import com.nexa.api.tenantmanagement.application.port.out.InvitationPersistencePort;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;

/** Bounded maintenance for invitations whose expiry has passed. */
@Component
@Profile("!test")
public final class InvitationExpirationJob {
	private final InvitationPersistencePort invitations;
	private final Clock clock;

	public InvitationExpirationJob(InvitationPersistencePort invitations, Clock clock) {
		this.invitations = invitations;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${nexa.tenant.invitation-expiry.poll-delay:PT1M}")
	public void expireBatch() {
		invitations.expirePending(clock.instant(), 250);
	}
}
