package com.nexa.api.salescommitment.infrastructure;

import com.nexa.api.salescommitment.application.publicapi.SalesOrderSupportProjectionQuery;
import com.nexa.api.salescommitment.infrastructure.persistence.JdbcTenantSalesOrderSupportProjectionQueryFactory;
import com.nexa.api.shared.application.error.ApiResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SalesOrderSupportProjectionQueryTests {
	@Test
	void queryScopesTheReadByTenantWorkspaceAndOrder() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		UUID tenantId = UUID.randomUUID();
		UUID workspaceId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		var expected = new SalesOrderSupportProjectionQuery.Snapshot("CONFIRMED", new BigDecimal("18.50"), "PEN", 4);
		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
			assertThat(invocation.<String>getArgument(0)).contains("sales.sales_order", "tenant_id=?", "workspace_id=?");
			assertThat(invocation.getArguments()).containsExactly(invocation.getArgument(0), invocation.getArgument(1),
					orderId, tenantId, workspaceId);
			return List.of(expected);
		});

		SalesOrderSupportProjectionQuery query = new JdbcTenantSalesOrderSupportProjectionQueryFactory().bindTo(jdbc);

		assertThat(query.find(tenantId, workspaceId, orderId)).isEqualTo(expected);
	}

	@Test
	void unknownOrderDoesNotProduceAnUnscopedProjection() {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
		SalesOrderSupportProjectionQuery query = new JdbcTenantSalesOrderSupportProjectionQueryFactory().bindTo(jdbc);

		assertThatThrownBy(() -> query.find(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
				.isInstanceOf(ApiResourceNotFoundException.class);
	}
}
