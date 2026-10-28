package com.nexa.api.salescommitment.infrastructure.salesorder;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountQuery;
import com.nexa.api.payments.application.publicapi.PaymentConfirmationQuery;
import com.nexa.api.salescommitment.application.exception.SalesResourceNotFoundException;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderFilter;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderSalesOwnerScope;
import com.nexa.api.salescommitment.application.salesorder.model.SalesOrderView;
import com.nexa.api.salescommitment.application.port.CommercialCommitmentPort;
import com.nexa.api.shared.application.port.out.ChangeEventPersistencePort;
import com.nexa.api.shared.application.port.out.CanonicalOutboxPort;
import com.nexa.api.creditreceivables.application.publicapi.FinancialAdjustmentCommands;
import com.nexa.api.creditreceivables.application.publicapi.ReceivableCommands;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SalesOrderSalesOwnerVisibilityTests {
	private static final SalesOrderFilter FILTER = new SalesOrderFilter(null, 0, 10, "createdAt,desc");

	@Test
	void listDetailAndEventsApplyTenantWorkspaceAndActiveAssignedAccountPredicatesInSql() {
		CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
		SalesOrderPersistenceAdapter persistence = persistence(jdbc);
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID firstAccountId = UUID.randomUUID();
		UUID secondAccountId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		SalesOrderSalesOwnerScope scope = new SalesOrderSalesOwnerScope(Set.of(firstAccountId, secondAccountId));
		List<UUID> orderedAccountIds = scope.activeClientAccountIds().stream().sorted().toList();

		persistence.listForSalesOwner(tenantId.toString(), workspaceId.toString(), scope, FILTER);

		assertThat(jdbc.queries).hasSize(2);
		assertThat(jdbc.queries.get(0).sql()).contains(
				"where o.tenant_id=? and o.workspace_id=? and o.client_account_id in (?,?)");
		assertThat(jdbc.queries.get(0).arguments()).containsExactly(tenantId, workspaceId,
				orderedAccountIds.get(0), orderedAccountIds.get(1));
		assertThat(jdbc.queries.get(1).sql()).contains(
				"where o.tenant_id=? and o.workspace_id=? and o.client_account_id in (?,?)");
		assertThat(jdbc.queries.get(1).arguments()).containsExactly(tenantId, workspaceId,
				orderedAccountIds.get(0), orderedAccountIds.get(1), 10, 0);

		jdbc.queries.clear();
		assertThat(persistence.findForSalesOwner(tenantId.toString(), workspaceId.toString(), scope,
				orderId.toString())).isEmpty();
		assertThat(jdbc.queries).singleElement().satisfies(query -> {
			assertThat(query.sql()).contains("where o.tenant_id=? and o.workspace_id=?")
					.contains("o.client_account_id in (?,?)").contains("and o.id=?");
			assertThat(query.arguments()).containsExactly(tenantId, workspaceId,
					orderedAccountIds.get(0), orderedAccountIds.get(1), orderId);
		});

		jdbc.queries.clear();
		jdbc.resultSetResult = Optional.of(order(orderId, tenantId, workspaceId, firstAccountId));
		assertThat(persistence.eventsForSalesOwner(tenantId.toString(), workspaceId.toString(), scope,
				orderId.toString())).isEmpty();
		assertThat(jdbc.queries).hasSize(2);
		assertThat(jdbc.queries.get(0).sql()).contains("o.client_account_id in (?,?)");
		assertThat(jdbc.queries.get(1).sql()).contains("join sales.sales_order o")
				.contains("e.tenant_id=? and e.workspace_id=? and e.sales_order_id=?")
				.contains("o.tenant_id=? and o.workspace_id=? and o.client_account_id in (?,?)");
		assertThat(jdbc.queries.get(1).arguments()).containsExactly(tenantId, workspaceId, orderId,
				tenantId, workspaceId, orderedAccountIds.get(0), orderedAccountIds.get(1));
	}

	@Test
	void emptySalesOwnerScopeFailsClosedWithoutQueryingOrders() {
		CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
		SalesOrderPersistenceAdapter persistence = persistence(jdbc);
		SalesOrderSalesOwnerScope scope = new SalesOrderSalesOwnerScope(Set.of());
		String tenantId = UUID.randomUUID().toString();
		String workspaceId = UUID.randomUUID().toString();
		String orderId = UUID.randomUUID().toString();

		assertThat(persistence.listForSalesOwner(tenantId, workspaceId, scope, FILTER).items()).isEmpty();
		assertThat(persistence.findForSalesOwner(tenantId, workspaceId, scope, orderId)).isEmpty();
		assertThatThrownBy(() -> persistence.eventsForSalesOwner(tenantId, workspaceId, scope, orderId))
				.isInstanceOf(SalesResourceNotFoundException.class);
		assertThat(jdbc.queries).isEmpty();
	}

	private static SalesOrderPersistenceAdapter persistence(JdbcTemplate jdbc) {
		return new SalesOrderPersistenceAdapter(jdbc, mock(ChangeEventPersistencePort.class),
				mock(CommercialCommitmentPort.class), mock(CustomerAccountQuery.class),
				mock(PaymentConfirmationQuery.class), mock(ReceivableCommands.class),
				mock(FinancialAdjustmentCommands.class), Clock.systemUTC(), new tools.jackson.databind.ObjectMapper(),
				mock(CanonicalOutboxPort.class));
	}

	private static SalesOrderView order(UUID orderId, UUID tenantId, UUID workspaceId, UUID accountId) {
		return new SalesOrderView(orderId.toString(), "SO-TEST", tenantId.toString(), workspaceId.toString(),
				accountId.toString(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), null,
				com.nexa.api.salescommitment.domain.model.purchaserequest.PurchaseRequestPriority.NORMAL,
				null, null, null, null, "PEN", BigDecimal.ZERO, "CONFIRMED", Instant.EPOCH, Instant.EPOCH,
				null, null, null, null, 1, List.of());
	}

	private record CapturedQuery(String sql, List<Object> arguments) { }

	private static final class CapturingJdbcTemplate extends JdbcTemplate {
		private final List<CapturedQuery> queries = new ArrayList<>();
		private Object resultSetResult = Optional.empty();

		@Override
		public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
			queries.add(capture(sql, args));
			return requiredType.cast(0L);
		}

		@Override
		public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
			queries.add(capture(sql, args));
			return List.of();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <T> T query(String sql, ResultSetExtractor<T> resultSetExtractor, Object... args) {
			queries.add(capture(sql, args));
			return (T) resultSetResult;
		}

		private static CapturedQuery capture(String sql, Object[] args) {
			return new CapturedQuery(sql, List.copyOf(Arrays.asList(args)));
		}
	}
}
