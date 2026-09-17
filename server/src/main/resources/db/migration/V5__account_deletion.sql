ALTER TABLE moalog.users ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_users_deleted_at ON moalog.users(deleted_at);
