package com.nexa.api.tenantaccessgovernance.support.application.service;

import com.nexa.api.tenantaccessgovernance.support.application.model.SupportRequestView;
import com.nexa.api.tenantaccessgovernance.support.application.port.in.SupportConsoleUseCase;
import com.nexa.api.tenantaccessgovernance.support.application.port.out.SupportRequestPersistencePort;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrant;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportOrderReadGrantIssuer;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderProjection;
import com.nexa.api.tenantaccessgovernance.support.application.publicapi.SupportSalesOrderReadQueryFactory;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.application.model.CurrentAccessContext;
import com.nexa.api.tenantaccessgovernance.tenantmanagement.domain.publicapi.MembershipRole;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SupportConsoleService implements SupportConsoleUseCase {
	private static final Duration MAX_GRANT_DURATION = Duration.ofSeconds(3_600);
	private static final int MAX_LIST_LIMIT = 100;
	private final SupportRequestPersistencePort persistence;
	private final SupportSalesOrderReadQueryFactory orderReadQueries;

	public SupportConsoleService(SupportRequestPersistencePort persistence,
			SupportSalesOrderReadQueryFactory orderReadQueries) {
		this.persistence = Objects.requireNonNull(persistence);
		this.orderReadQueries = Objects.requireNonNull(orderReadQueries);
	}

	@Override
	@Transactional
	public SupportRequestView request(UUID requesterOperatorId, UUID tenantId, UUID workspaceId,
			UUID salesOrderId, Instant expiresAt) {
		Instant now = Instant.now();
		if (expiresAt == null || !expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(MAX_GRANT_DURATION))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support deadline must be within one hour.");
		}
		return persistence.create(UUID.randomUUID(), required(tenantId), required(workspaceId),
			required(salesOrderId), required(requesterOperatorId), expiresAt, now);
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupportRequestView> listInternal(UUID operatorId, int limit) {
		return persistence.listInternal(required(operatorId), Instant.now(), boundedLimit(limit));
	}

	@Override
	@Transactional(readOnly = true)
	public List<SupportRequestView> listOwner(CurrentAccessContext ownerContext, int limit) {
		requireCompanyOwner(ownerContext);
		return persistence.listOwner(ownerContext.tenantId().value(), ownerContext.workspaceId().value(),
			Instant.now(), boundedLimit(limit));
	}

	@Override
	@Transactional
	public SupportRequestView consent(UUID requestId, CurrentAccessContext ownerContext) {
		requireCompanyOwner(ownerContext);
		return persistence.consent(required(requestId), ownerContext.tenantId().value(), ownerContext.workspaceId().value(),
			ownerContext.userId().value(), ownerContext.membershipId().value(), Instant.now())
			.orElseThrow(() -> conflict("Support consent is unavailable for this exact scope or deadline."));
	}

	@Override
	@Transactional
	public SupportRequestView approve(UUID requestId, UUID approverOperatorId) {
		return persistence.approve(required(requestId), required(approverOperatorId), Instant.now())
			.orElseThrow(() -> conflict("A different operator must approve a current Owner-consented request."));
	}

	@Override
	@Transactional
	public SupportRequestView revokeByOperator(UUID requestId, UUID operatorId) {
		return persistence.revokeByOperator(required(requestId), required(operatorId), Instant.now())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active support request not found."));
	}

	@Override
	@Transactional
	public SupportRequestView revokeByOwner(UUID requestId, CurrentAccessContext ownerContext) {
		requireCompanyOwner(ownerContext);
		return persistence.revokeByOwner(required(requestId), ownerContext.tenantId().value(), ownerContext.workspaceId().value(),
			ownerContext.userId().value(), ownerContext.membershipId().value(), Instant.now())
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Active support request not found."));
	}

	@Override
	public SupportSalesOrderProjection readSalesOrder(UUID requestId, UUID requesterOperatorId) {
		UUID requestIdValue = required(requestId);
		UUID operatorId = required(requesterOperatorId);
		Instant now = Instant.now();
		SupportRequestView request = persistence.findForOperator(requestIdValue, operatorId, now)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved support request not found."));
		if (!requestIdValue.equals(request.id()) || !operatorId.equals(request.requestedByOperatorId())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approved support request not found.");
		}
		if (!"APPROVED".equals(request.status()) || !now.isBefore(request.expiresAt())) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "The support grant is no longer active.");
		}
		SupportOrderReadGrant grant = SupportOrderReadGrantIssuer.fromApprovedRequest(request, now);
		SupportSalesOrderProjection projection = orderReadQueries.open(grant).read();
		persistence.appendOrderRead(request, operatorId, Instant.now());
		return projection;
	}

	private static UUID required(UUID value) {
		if (value == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Support request identity and scope are required.");
		}
		return value;
	}

	private static int boundedLimit(int requested) {
		if (requested < 1) return 1;
		return Math.min(requested, MAX_LIST_LIMIT);
	}

	private static void requireCompanyOwner(CurrentAccessContext context) {
		if (context == null || !context.roles().contains(MembershipRole.COMPANY_OWNER)) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "A current Company Owner context is required.");
		}
	}

	private static ResponseStatusException conflict(String message) {
		return new ResponseStatusException(HttpStatus.CONFLICT, message);
	}
}
