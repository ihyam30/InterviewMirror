CREATE TABLE report_tasks (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    task_type VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
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
    CONSTRAINT ck_report_task_type CHECK (task_type IN ('REPORT', 'GAP_ANALYSIS')),
    CONSTRAINT ck_report_task_status CHECK (status IN ('PENDING', 'PROCESSING', 'SUCCESS', 'FAILED')),
    CONSTRAINT uq_report_task_interview_type UNIQUE (interview_id, task_type),
    CONSTRAINT fk_report_task_interview_owner FOREIGN KEY (interview_id, owner_id)
        REFERENCES interviews(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_report_tasks_queue ON report_tasks(task_type, status, queued_at);
CREATE INDEX ix_report_tasks_lease ON report_tasks(status, lease_until);
CREATE INDEX ix_report_tasks_owner ON report_tasks(owner_id, interview_id);

CREATE TABLE interview_reports (
    id UUID PRIMARY KEY,
    interview_id UUID NOT NULL UNIQUE,
    owner_id UUID NOT NULL,
    schema_version VARCHAR(24) NOT NULL,
    status VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    source_snapshot TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    generation_duration_ms BIGINT NOT NULL CHECK (generation_duration_ms >= 0),
    CONSTRAINT ck_interview_report_status CHECK (status IN ('READY', 'PARTIAL')),
    CONSTRAINT uq_interview_report_id_owner UNIQUE (id, owner_id),
    CONSTRAINT fk_interview_report_interview_owner FOREIGN KEY (interview_id, owner_id)
        REFERENCES interviews(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_interview_reports_owner_created ON interview_reports(owner_id, created_at DESC);

CREATE TABLE report_gap_analyses (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    interview_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    schema_version VARCHAR(24) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_report_gap_report UNIQUE (report_id),
    CONSTRAINT fk_report_gap_report_owner FOREIGN KEY (report_id, owner_id)
        REFERENCES interview_reports(id, owner_id) ON DELETE CASCADE,
    CONSTRAINT fk_report_gap_interview_owner FOREIGN KEY (interview_id, owner_id)
        REFERENCES interviews(id, owner_id) ON DELETE CASCADE
);

CREATE INDEX ix_report_gap_owner ON report_gap_analyses(owner_id, report_id);

CREATE TABLE report_pdf_artifacts (
    id UUID PRIMARY KEY,
    report_id UUID NOT NULL,
    owner_id UUID NOT NULL,
    object_key VARCHAR(300) NOT NULL UNIQUE,
    sha256 VARCHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes BETWEEN 1 AND 20971520),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_report_pdf_owner UNIQUE (report_id, owner_id),
    CONSTRAINT fk_report_pdf_report_owner FOREIGN KEY (report_id, owner_id)
        REFERENCES interview_reports(id, owner_id) ON DELETE CASCADE
);
