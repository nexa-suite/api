package com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence;

import com.nexa.api.tenantaccessgovernance.iam.application.port.out.PublicContactRequestPersistencePort;
import com.nexa.api.tenantaccessgovernance.iam.domain.model.publiccontact.PublicContactRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
@ConditionalOnProperty(prefix = "nexa.jdbc", name = "adapters-enabled", havingValue = "true", matchIfMissing = true)
public class JdbcPublicContactRequestPersistenceAdapter implements PublicContactRequestPersistencePort {
    private final JdbcTemplate jdbc;

    public JdbcPublicContactRequestPersistenceAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void save(PublicContactRequest request, String correlationId, String traceId) {
        jdbc.update("insert into iam.public_contact_request "
                        + "(id,request_type,full_name,work_email,company_name,message,source,status,correlation_id,trace_id,received_at) "
                        + "values (?,?,?,?,?,?, 'WEBSITE','RECEIVED',?,?,?)",
                request.id(), request.type().name(), request.fullName(), request.email(), request.companyName(), request.message(),
                correlationId, traceId, Timestamp.from(request.receivedAt()));
    }
}
