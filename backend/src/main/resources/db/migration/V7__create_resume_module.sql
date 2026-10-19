CREATE TABLE resumes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    file_hash VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size BETWEEN 1 AND 10485760),
    content_type VARCHAR(120) NOT NULL,
    storage_key VARCHAR(300) NOT NULL UNIQUE,
    storage_url VARCHAR(500),
    resume_text TEXT NOT NULL,
    structured_content TEXT NOT NULL,
    uploaded_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_accessed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    access_count BIGINT NOT NULL DEFAULT 1 CHECK (access_count >= 1),
    analyze_status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (analyze_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    analyze_error VARCHAR(500),
    analyze_updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    analyze_recovery_count INTEGER NOT NULL DEFAULT 0 CHECK (analyze_recovery_count >= 0),
    analyze_retry_count INTEGER NOT NULL DEFAULT 0 CHECK (analyze_retry_count BETWEEN 0 AND 3),
    analyze_attempt_id UUID,
    delete_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (delete_status IN ('ACTIVE','DELETING','DELETE_FAILED')),
    UNIQUE (user_id, file_hash),
    UNIQUE (id, user_id)
);
CREATE INDEX ix_resumes_user_uploaded ON resumes(user_id, uploaded_at DESC, id DESC);
CREATE INDEX ix_resumes_recovery ON resumes(delete_status, analyze_status, analyze_updated_at);

CREATE TABLE resume_analyses (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL,
    user_id UUID NOT NULL,
    attempt_id UUID NOT NULL UNIQUE,
    rubric_version VARCHAR(80) NOT NULL,
    overall_score INTEGER NOT NULL CHECK (overall_score BETWEEN 0 AND 100),
    content_score INTEGER NOT NULL CHECK (content_score BETWEEN 0 AND 15),
    structure_score INTEGER NOT NULL CHECK (structure_score BETWEEN 0 AND 15),
    skill_match_score INTEGER NOT NULL CHECK (skill_match_score BETWEEN 0 AND 20),
    expression_score INTEGER NOT NULL CHECK (expression_score BETWEEN 0 AND 10),
    project_score INTEGER NOT NULL CHECK (project_score BETWEEN 0 AND 40),
    strengths_json TEXT NOT NULL,
    suggestions_json TEXT NOT NULL,
    analyzed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (resume_id, user_id) REFERENCES resumes(id, user_id) ON DELETE CASCADE,
    CHECK (overall_score = content_score + structure_score + skill_match_score + expression_score + project_score)
);
CREATE INDEX ix_resume_analyses_latest ON resume_analyses(resume_id, analyzed_at DESC, id DESC);

CREATE TABLE resume_analysis_outbox (
    id UUID PRIMARY KEY,
    resume_id UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count BETWEEN 0 AND 3),
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_resume_outbox_pending ON resume_analysis_outbox(delivered_at, available_at);

CREATE TABLE resume_object_cleanup (
    id UUID PRIMARY KEY,
    resume_id UUID REFERENCES resumes(id) ON DELETE CASCADE,
    object_key VARCHAR(300) NOT NULL UNIQUE,
    cleanup_kind VARCHAR(16) NOT NULL DEFAULT 'DELETE' CHECK (cleanup_kind IN ('DELETE','COMPENSATION')),
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP WITH TIME ZONE,
    last_error VARCHAR(500)
);
CREATE INDEX ix_resume_cleanup_pending ON resume_object_cleanup(completed_at, available_at);

-- Apply to a fresh/reset development database; legacy resume IDs are not migrated.
ALTER TABLE interviews ADD COLUMN delete_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE interviews ADD CONSTRAINT fk_interview_resume_user
    FOREIGN KEY (resume_id, owner_id) REFERENCES resumes(id, user_id);
CREATE INDEX ix_interviews_resume_user ON interviews(resume_id, owner_id);
