ALTER TABLE business_documents.business_document
    ADD COLUMN replacement_of_document_id UUID;

ALTER TABLE business_documents.business_document
    ADD CONSTRAINT fk_business_document_replacement_of
        FOREIGN KEY (tenant_id, workspace_id, replacement_of_document_id)
        REFERENCES business_documents.business_document (tenant_id, workspace_id, id)
        ON DELETE RESTRICT;

CREATE INDEX ix_business_document_replacement_history
    ON business_documents.business_document (tenant_id, workspace_id, replacement_of_document_id, version DESC)
    WHERE replacement_of_document_id IS NOT NULL;
