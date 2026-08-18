package com.nexa.api.sales.infrastructure.export;

import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummaryLineSnapshot;
import com.nexa.api.sales.application.salesorder.export.model.SalesOrderSummarySnapshot;
import com.nexa.api.sales.application.salesorder.export.port.SalesOrderSummaryProjectionPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("!test")
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcSalesOrderSummaryProjectionAdapter implements SalesOrderSummaryProjectionPort {
	private final JdbcTemplate jdbc;

	public JdbcSalesOrderSummaryProjectionAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	@Override
	public Optional<SalesOrderSummarySnapshot> find(String tenantId, String workspaceId, String clientAccountId, String orderId) {
		StringBuilder sql = new StringBuilder("select o.id,o.number,o.tenant_id,o.workspace_id,o.client_account_id,o.priority,o.requested_delivery_date,o.delivery_snapshot,o.payment_option,o.notes,o.currency,o.total_amount,o.status,o.created_at from sales.sales_order o where o.tenant_id=? and o.workspace_id=?");
		List<Object> args = new ArrayList<>(List.of(uuid(tenantId), uuid(workspaceId)));
		if (clientAccountId != null) {
			sql.append(" and o.client_account_id=?");
			args.add(uuid(clientAccountId));
		}
		sql.append(" and o.id=?");
		args.add(uuid(orderId));
		return jdbc.query(sql.toString(), rs -> rs.next() ? Optional.of(snapshot(rs)) : Optional.empty(), args.toArray());
	}

	private SalesOrderSummarySnapshot snapshot(ResultSet rs) throws java.sql.SQLException {
		String id = rs.getObject(1).toString();
		List<SalesOrderSummaryLineSnapshot> lines = jdbc.query("select catalog_item_id,item_name_snapshot,presentation_snapshot,quantity,unit,unit_price_amount,unit_price_currency,line_subtotal from sales.sales_order_line where sales_order_id=? order by created_at,id",
				(line, row) -> new SalesOrderSummaryLineSnapshot(line.getString(1), line.getString(2), line.getString(3), line.getBigDecimal(4), line.getString(5), line.getBigDecimal(6), line.getString(7), line.getBigDecimal(8)), uuid(id));
		return new SalesOrderSummarySnapshot(id, rs.getString(2), rs.getObject(3).toString(), rs.getObject(4).toString(),
				string(rs, 5), rs.getString(6), date(rs, 7), rs.getString(8), rs.getString(9), rs.getString(10),
				rs.getString(11), rs.getBigDecimal(12), rs.getString(13), rs.getTimestamp(14).toInstant(), lines);
	}

	private static LocalDate date(ResultSet rs, int index) throws java.sql.SQLException {
		java.sql.Date date = rs.getDate(index);
		return date == null ? null : date.toLocalDate();
	}
	private static String string(ResultSet rs, int index) throws java.sql.SQLException {
		Object value = rs.getObject(index);
		return value == null ? null : value.toString();
	}
	private static UUID uuid(String value) { return UUID.fromString(value); }
}
