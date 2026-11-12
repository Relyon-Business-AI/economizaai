-- Admin dashboard (GET /admin/dashboard) filters every aggregate by a createdAt window.
-- Neither table had an index on created_at, so each KPI was a full scan.
CREATE INDEX IF NOT EXISTS idx_receipts_created_at ON receipts (created_at);
CREATE INDEX IF NOT EXISTS idx_users_created_at ON users (created_at);
