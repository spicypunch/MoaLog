CREATE TABLE moalog.users (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120),
    email VARCHAR(320),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_users_display_name_not_blank CHECK (display_name IS NULL OR LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_users_email_not_blank CHECK (email IS NULL OR LENGTH(TRIM(email)) > 0)
);

CREATE TABLE moalog.auth_identities (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    provider VARCHAR(20) NOT NULL,
    provider_subject VARCHAR(255) NOT NULL,
    provider_email VARCHAR(320),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_auth_identities_user FOREIGN KEY (user_id) REFERENCES moalog.users(id) ON DELETE CASCADE,
    CONSTRAINT uq_auth_identities_provider_subject UNIQUE (provider, provider_subject),
    CONSTRAINT ck_auth_identities_provider CHECK (provider IN ('GOOGLE', 'APPLE')),
    CONSTRAINT ck_auth_identities_subject_not_blank CHECK (LENGTH(TRIM(provider_subject)) > 0)
);

CREATE INDEX ix_auth_identities_user_id ON moalog.auth_identities(user_id);

CREATE TABLE moalog.auth_login_challenges (
    id UUID PRIMARY KEY,
    nonce_hash VARCHAR(64) NOT NULL,
    device_hash VARCHAR(64) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_auth_login_challenges_nonce_hash UNIQUE (nonce_hash),
    CONSTRAINT ck_auth_login_challenges_hash_length CHECK (LENGTH(nonce_hash) = 64),
    CONSTRAINT ck_auth_login_challenges_device_hash_length CHECK (LENGTH(device_hash) = 64),
    CONSTRAINT ck_auth_login_challenges_source_hash_length CHECK (LENGTH(source_hash) = 64),
    CONSTRAINT ck_auth_login_challenges_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_auth_login_challenges_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_login_challenges_expires_at ON moalog.auth_login_challenges(expires_at);
CREATE INDEX ix_auth_login_challenges_device_expiry
    ON moalog.auth_login_challenges(device_hash, expires_at);
CREATE INDEX ix_auth_login_challenges_source_expiry
    ON moalog.auth_login_challenges(source_hash, expires_at);

CREATE TABLE moalog.auth_login_challenge_capacity (
    id SMALLINT PRIMARY KEY,
    CONSTRAINT ck_auth_login_challenge_capacity_singleton CHECK (id = 1)
);

INSERT INTO moalog.auth_login_challenge_capacity(id) VALUES (1);

CREATE TABLE moalog.auth_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    device_id UUID NOT NULL,
    platform VARCHAR(20) NOT NULL,
    app_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES moalog.users(id) ON DELETE CASCADE,
    CONSTRAINT ck_auth_sessions_platform CHECK (platform IN ('ANDROID', 'IOS')),
    CONSTRAINT ck_auth_sessions_app_version_not_blank CHECK (LENGTH(TRIM(app_version)) > 0),
    CONSTRAINT ck_auth_sessions_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_sessions_user_id ON moalog.auth_sessions(user_id);
CREATE INDEX ix_auth_sessions_device_id ON moalog.auth_sessions(device_id);
CREATE INDEX ix_auth_sessions_expires_at ON moalog.auth_sessions(expires_at);

CREATE TABLE moalog.auth_refresh_tokens (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_auth_refresh_tokens_session FOREIGN KEY (session_id) REFERENCES moalog.auth_sessions(id) ON DELETE CASCADE,
    CONSTRAINT uq_auth_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_auth_refresh_tokens_status CHECK (status IN ('ACTIVE', 'USED')),
    CONSTRAINT ck_auth_refresh_tokens_hash_length CHECK (LENGTH(token_hash) = 64),
    CONSTRAINT ck_auth_refresh_tokens_expiry CHECK (expires_at > created_at)
);

CREATE INDEX ix_auth_refresh_tokens_session_id ON moalog.auth_refresh_tokens(session_id);
