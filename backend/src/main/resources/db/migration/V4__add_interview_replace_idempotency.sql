ALTER TABLE interview_turns ADD COLUMN replace_request_id VARCHAR(100);
ALTER TABLE interview_turns ADD COLUMN replace_request_fingerprint VARCHAR(64);
ALTER TABLE interview_turns ADD COLUMN replace_claimed_at TIMESTAMP WITH TIME ZONE;

CREATE UNIQUE INDEX uq_interview_turn_replace_request
    ON interview_turns(interview_id, replace_request_id);

CREATE INDEX ix_interview_turn_replace_recovery
    ON interview_turns(status, replace_claimed_at);
