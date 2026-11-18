package com.nexa.api.tenantaccessgovernance.iam.legalacceptance;

import com.nexa.api.tenantaccessgovernance.iam.application.service.LegalAcceptance;
import com.nexa.api.tenantaccessgovernance.iam.application.model.LegalAcceptanceModels;
import com.nexa.api.tenantaccessgovernance.iam.application.port.out.LegalAcceptanceStore;
import com.nexa.api.tenantaccessgovernance.iam.infrastructure.persistence.JdbcLegalAcceptanceStore;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class LegalAcceptancePostgresTests {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4-alpine");
    static JdbcTemplate jdbc;

    @BeforeAll static void setup() throws Exception {
        var ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("create role nexa_runtime login noinherit password 'test-only-runtime'");
        jdbc.execute("create role nexa_migrator login noinherit password 'test-only-migrator'");
        jdbc.execute("create table public.flyway_schema_history (version varchar(50), success boolean)");
        jdbc.execute("insert into public.flyway_schema_history values ('189', true)");
        jdbc.execute("create schema iam");
        jdbc.execute("grant usage on schema iam to nexa_runtime, nexa_migrator");
        jdbc.execute("create table iam.user_account (id uuid primary key)");
        jdbc.execute(java.nio.file.Files.readString(java.nio.file.Path.of("ops/database/prepare-v190-individual-legal-acceptance.sql")));
        var migrator = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "nexa_migrator", "test-only-migrator"));
        migrator.execute(new String(new ClassPathResource("db/migration/V190__record_individual_legal_acceptance.sql").getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test void persistsOnlyOneAcceptancePerUserVersionAndPreservesFirstTimestamp() {
        var user = UUID.randomUUID();
        jdbc.update("insert into iam.user_account (id) values (?)", user);
        var store = new JdbcLegalAcceptanceStore(jdbc);
        store.insertIfAbsent(user, "mobile-terms", "1", LegalAcceptance.DIGEST);
        var first = store.find(user, "mobile-terms", "1").orElseThrow();
        store.insertIfAbsent(user, "mobile-terms", "1", LegalAcceptance.DIGEST);
        assertThat(store.find(user, "mobile-terms", "1")).contains(first);
        assertThat(jdbc.queryForObject("select count(*) from iam.legal_acceptance where user_id='" + user + "'", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> store.insertIfAbsent(UUID.randomUUID(), "mobile-terms", "1", LegalAcceptance.DIGEST))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void isolatesUsersAndRuntimeCanOnlyReadAndInsert() {
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        jdbc.update("insert into iam.user_account (id) values (?), (?)", first, second);
        var runtime = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "nexa_runtime", "test-only-runtime"));
        var store = new JdbcLegalAcceptanceStore(runtime);
        var service = new LegalAcceptance(store);
        var request = new LegalAcceptanceModels.Request(LegalAcceptance.DOCUMENT_ID, LegalAcceptance.VERSION, LegalAcceptance.DIGEST);
        service.accept(first, request);
        assertThat(service.current(first).accepted()).isTrue();
        assertThat(service.current(second).accepted()).isFalse();
        service.accept(second, request);
        assertThat(service.current(first).accepted()).isTrue();
        assertThat(service.current(second).accepted()).isTrue();
        assertThatThrownBy(() -> runtime.update("update iam.legal_acceptance set content_digest=? where user_id=?", LegalAcceptance.DIGEST, first))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table legal_acceptance");
        assertThatThrownBy(() -> runtime.update("delete from iam.legal_acceptance where user_id=?", first))
                .isInstanceOf(org.springframework.dao.DataAccessException.class).hasRootCauseMessage("ERROR: permission denied for table legal_acceptance");
    }

}
