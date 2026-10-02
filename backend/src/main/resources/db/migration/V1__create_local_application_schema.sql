CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    email VARCHAR(160) NOT NULL UNIQUE,
    display_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE demo_resources (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    resource_type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content TEXT NOT NULL DEFAULT '',
    file_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_demo_resource_type CHECK (length(resource_type) BETWEEN 1 AND 32)
);

CREATE INDEX ix_demo_resources_owner_updated ON demo_resources(owner_id, updated_at DESC);
CREATE INDEX ix_demo_resources_owner_type ON demo_resources(owner_id, resource_type);

CREATE TABLE stored_files (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    object_key VARCHAR(300) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_stored_files_owner_created ON stored_files(owner_id, created_at DESC);

ALTER TABLE demo_resources
    ADD CONSTRAINT fk_demo_resources_file FOREIGN KEY (file_id) REFERENCES stored_files(id) ON DELETE SET NULL;
