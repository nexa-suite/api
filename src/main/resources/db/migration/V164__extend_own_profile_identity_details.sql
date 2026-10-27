ALTER TABLE iam.user_account
    ADD COLUMN first_name VARCHAR(100),
    ADD COLUMN last_name VARCHAR(100),
    ADD COLUMN document_type VARCHAR(16),
    ADD COLUMN document_number VARCHAR(32),
    ADD COLUMN document_country_code VARCHAR(2),
    ADD CONSTRAINT ck_user_account_first_name_nonblank
        CHECK (first_name IS NULL OR btrim(first_name) <> ''),
    ADD CONSTRAINT ck_user_account_last_name_nonblank
        CHECK (last_name IS NULL OR btrim(last_name) <> ''),
    ADD CONSTRAINT ck_user_account_document_type
        CHECK (document_type IS NULL OR document_type IN ('DNI', 'CE', 'PASSPORT', 'OTHER')),
    ADD CONSTRAINT ck_user_account_document_tuple
        CHECK (
            (document_type IS NULL AND document_number IS NULL AND document_country_code IS NULL)
            OR (document_type IS NOT NULL AND document_number IS NOT NULL AND document_country_code IS NOT NULL)
        ),
    ADD CONSTRAINT ck_user_account_document_country_code
        CHECK (document_country_code IS NULL OR document_country_code ~ '^[A-Z]{2}$'),
    ADD CONSTRAINT ck_user_account_document_number
        CHECK (
            document_number IS NULL
            OR (
                document_number ~ '^[A-Z0-9]{1,32}$'
                AND NOT (document_type = 'DNI' AND document_country_code = 'PE' AND document_number !~ '^[0-9]{8}$')
            )
        );
