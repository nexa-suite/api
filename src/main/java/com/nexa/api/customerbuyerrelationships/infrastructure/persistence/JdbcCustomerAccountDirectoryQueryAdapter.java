package com.nexa.api.customerbuyerrelationships.infrastructure.persistence;

import com.nexa.api.customerbuyerrelationships.application.publicapi.CustomerAccountDirectoryQuery;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Returns only account identifier, commercial name and lifecycle state within explicit Tenant scope. */
public final class JdbcCustomerAccountDirectoryQueryAdapter implements CustomerAccountDirectoryQuery {
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_SEARCH_LENGTH = 120;

    private final JdbcTemplate jdbc;

    public JdbcCustomerAccountDirectoryQueryAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "Tenant JDBC session is required");
    }

    @Override
    public Page list(UUID tenantId, UUID workspaceId, String search, String status, int page, int size) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(workspaceId, "Workspace id is required");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Customer Account directory pagination is invalid");
        }

        String normalizedSearch = normalizeSearch(search);
        String normalizedStatus = normalizeStatus(status);
        StringBuilder where = new StringBuilder(" where a.tenant_id=? and a.workspace_id=?");
        List<Object> arguments = new ArrayList<>(List.of(tenantId, workspaceId));
        if (normalizedSearch != null) {
            where.append(" and lower(a.commercial_name) like ?");
            arguments.add("%" + normalizedSearch.toLowerCase(Locale.ROOT) + "%");
        }
        if (normalizedStatus != null) {
            where.append(" and a.status=?");
            arguments.add(normalizedStatus);
        }

        Long count = jdbc.queryForObject("select count(*) from sales.client_account a" + where,
                Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        List<Entry> items = jdbc.query("select a.id,a.commercial_name,a.status from sales.client_account a" + where
                        + " order by lower(a.commercial_name),a.id limit ? offset ?",
                (rs, row) -> new Entry(rs.getObject("id", UUID.class), rs.getString("commercial_name"),
                        rs.getString("status")), pageArguments.toArray());
        return new Page(items, page, size, count == null ? 0 : count);
    }

    @Override
    public Optional<Entry> find(UUID tenantId, UUID workspaceId, UUID clientAccountId) {
        Objects.requireNonNull(tenantId, "Tenant id is required");
        Objects.requireNonNull(workspaceId, "Workspace id is required");
        Objects.requireNonNull(clientAccountId, "Client Account id is required");
        return jdbc.query("select a.id,a.commercial_name,a.status from sales.client_account a "
                        + "where a.tenant_id=? and a.workspace_id=? and a.id=?",
                (rs, row) -> new Entry(rs.getObject("id", UUID.class), rs.getString("commercial_name"),
                        rs.getString("status")), tenantId, workspaceId, clientAccountId)
                .stream().findFirst();
    }

    private static String normalizeSearch(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > MAX_SEARCH_LENGTH) {
            throw new IllegalArgumentException("Customer Account directory search is too long");
        }
        return normalized;
    }

    private static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(normalized) && !"SUSPENDED".equals(normalized)) {
            throw new IllegalArgumentException("Customer Account directory status is invalid");
        }
        return normalized;
    }
}
