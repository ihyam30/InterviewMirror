ALTER TABLE stored_files ADD CONSTRAINT uq_stored_files_id_owner UNIQUE (id, owner_id);

CREATE TABLE managed_documents (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    file_id UUID,
    document_type VARCHAR(24) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL,
    parsed_content TEXT NOT NULL DEFAULT '{}',
    content TEXT NOT NULL DEFAULT '{}',
    content_version INTEGER NOT NULL DEFAULT 1 CHECK (content_version >= 1),
    confirmed_version INTEGER,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_managed_document_type CHECK (document_type IN ('RESUME', 'QUESTION_BANK')),
    CONSTRAINT ck_managed_document_status CHECK (status IN ('PENDING', 'PROCESSING', 'PARSED', 'FAILED', 'CONFIRMED', 'DELETING', 'DELETE_FAILED')),
    CONSTRAINT ck_managed_document_confirmation CHECK (
        (status = 'CONFIRMED' AND confirmed_at IS NOT NULL AND confirmed_version = content_version)
        OR (status <> 'CONFIRMED' AND confirmed_at IS NULL AND confirmed_version IS NULL)
    ),
    CONSTRAINT uq_managed_documents_id_owner UNIQUE (id, owner_id),
    CONSTRAINT fk_managed_document_file_owner FOREIGN KEY (file_id, owner_id)
        REFERENCES stored_files(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_managed_documents_owner_type_updated ON managed_documents(owner_id, document_type, updated_at DESC);
CREATE INDEX ix_managed_documents_owner_status ON managed_documents(owner_id, status);
CREATE INDEX ix_managed_documents_file ON managed_documents(file_id);

CREATE TABLE document_parse_tasks (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL UNIQUE,
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_code VARCHAR(64),
    error_message VARCHAR(240),
    queued_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT CHECK (duration_ms IS NULL OR duration_ms >= 0),
    worker_id VARCHAR(120),
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_document_parse_task_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT fk_document_parse_task_owner FOREIGN KEY (document_id, owner_id)
        REFERENCES managed_documents(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_document_parse_tasks_queue ON document_parse_tasks(status, queued_at);
CREATE INDEX ix_document_parse_tasks_owner ON document_parse_tasks(owner_id, id);
CREATE INDEX ix_document_parse_tasks_lease ON document_parse_tasks(status, lease_until);
