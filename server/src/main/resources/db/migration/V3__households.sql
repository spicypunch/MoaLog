CREATE TABLE moalog.households (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    base_year INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_households_name_not_blank CHECK (LENGTH(TRIM(name)) > 0),
    CONSTRAINT ck_households_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_households_base_year CHECK (base_year BETWEEN 1900 AND 9999),
    CONSTRAINT ck_households_version CHECK (version > 0)
);

CREATE TABLE moalog.ledger_members (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    member_order INTEGER NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_ledger_members_household FOREIGN KEY (household_id)
        REFERENCES moalog.households(id) ON DELETE CASCADE,
    CONSTRAINT uq_ledger_members_household_order UNIQUE (household_id, member_order),
    CONSTRAINT uq_ledger_members_household_id_id UNIQUE (household_id, id),
    CONSTRAINT ck_ledger_members_order CHECK (member_order IN (0, 1)),
    CONSTRAINT ck_ledger_members_display_name_not_blank CHECK (LENGTH(TRIM(display_name)) > 0),
    CONSTRAINT ck_ledger_members_version CHECK (version > 0)
);

CREATE INDEX ix_ledger_members_household_id ON moalog.ledger_members(household_id);

CREATE TABLE moalog.household_memberships (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    user_id UUID NOT NULL,
    ledger_member_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_household_memberships_household FOREIGN KEY (household_id)
        REFERENCES moalog.households(id) ON DELETE CASCADE,
    CONSTRAINT fk_household_memberships_user FOREIGN KEY (user_id)
        REFERENCES moalog.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_household_memberships_ledger_member FOREIGN KEY (household_id, ledger_member_id)
        REFERENCES moalog.ledger_members(household_id, id) ON DELETE CASCADE,
    CONSTRAINT uq_household_memberships_household_user UNIQUE (household_id, user_id),
    CONSTRAINT uq_household_memberships_ledger_member UNIQUE (ledger_member_id),
    CONSTRAINT ck_household_memberships_role CHECK (role IN ('OWNER', 'MEMBER')),
    CONSTRAINT ck_household_memberships_version CHECK (version > 0)
);

CREATE INDEX ix_household_memberships_user_id ON moalog.household_memberships(user_id);
CREATE INDEX ix_household_memberships_household_id ON moalog.household_memberships(household_id);

CREATE TABLE moalog.annual_savings_targets (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    target_year INTEGER NOT NULL,
    amount_won BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_annual_savings_targets_household FOREIGN KEY (household_id)
        REFERENCES moalog.households(id) ON DELETE CASCADE,
    CONSTRAINT uq_annual_savings_targets_household_year UNIQUE (household_id, target_year),
    CONSTRAINT ck_annual_savings_targets_year CHECK (target_year BETWEEN 1900 AND 9999),
    CONSTRAINT ck_annual_savings_targets_amount CHECK (amount_won >= 0),
    CONSTRAINT ck_annual_savings_targets_version CHECK (version > 0)
);

CREATE INDEX ix_annual_savings_targets_household_id ON moalog.annual_savings_targets(household_id);

CREATE TABLE moalog.household_invitations (
    id UUID PRIMARY KEY,
    household_id UUID NOT NULL,
    target_ledger_member_id UUID NOT NULL,
    created_by_user_id UUID NOT NULL,
    accepted_by_user_id UUID,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at TIMESTAMP WITH TIME ZONE,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_household_invitations_household FOREIGN KEY (household_id)
        REFERENCES moalog.households(id) ON DELETE CASCADE,
    CONSTRAINT fk_household_invitations_target_member FOREIGN KEY (household_id, target_ledger_member_id)
        REFERENCES moalog.ledger_members(household_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_household_invitations_created_by FOREIGN KEY (created_by_user_id)
        REFERENCES moalog.users(id) ON DELETE RESTRICT,
    CONSTRAINT fk_household_invitations_accepted_by FOREIGN KEY (accepted_by_user_id)
        REFERENCES moalog.users(id) ON DELETE RESTRICT,
    CONSTRAINT uq_household_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_household_invitations_hash_length CHECK (LENGTH(token_hash) = 64),
    CONSTRAINT ck_household_invitations_status CHECK (status IN ('ACTIVE', 'ACCEPTED', 'REVOKED')),
    CONSTRAINT ck_household_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_household_invitations_version CHECK (version > 0),
    CONSTRAINT ck_household_invitations_terminal_state CHECK (
        (status = 'ACTIVE' AND accepted_by_user_id IS NULL AND accepted_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'ACCEPTED' AND accepted_by_user_id IS NOT NULL AND accepted_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'REVOKED' AND accepted_by_user_id IS NULL AND accepted_at IS NULL AND cancelled_at IS NOT NULL)
    )
);

CREATE INDEX ix_household_invitations_household_id ON moalog.household_invitations(household_id);
CREATE INDEX ix_household_invitations_target_member ON moalog.household_invitations(target_ledger_member_id);
CREATE INDEX ix_household_invitations_expires_at ON moalog.household_invitations(expires_at);
