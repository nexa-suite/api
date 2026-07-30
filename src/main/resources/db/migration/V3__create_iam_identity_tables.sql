CREATE TABLE iam.user_account (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    normalized_email VARCHAR(254) NOT NULL UNIQUE,
    username VARCHAR(64) NOT NULL,
    normalized_username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(160) NOT NULL,
    preferred_language VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE iam.password_credential (
    user_id UUID PRIMARY KEY,
    password_hash VARCHAR(255) NOT NULL,
    algorithm VARCHAR(32) NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_password_credential_user
        FOREIGN KEY (user_id) REFERENCES iam.user_account (id)
);
