package com.nexa.api.businessdocuments.tenantdatabase;

import com.nexa.api.businessdocuments.application.publicapi.BusinessEvidenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;

/** Binds BC-09 evidence availability reads to one routed Tenant session. */
@FunctionalInterface
public interface TenantBusinessEvidenceQueryFactory {
    BusinessEvidenceQuery bindTo(JdbcTemplate tenantJdbc);
}
