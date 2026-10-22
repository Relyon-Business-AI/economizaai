-- Attribute AI (LLM) cost per user so the per-user COGS panel can include it.
-- Nullable: admin/batch sweeps (rule/brand suggestion, consensus, enrichment) have
-- no triggering user and stay NULL; user-triggered activities (photo extract, HTML
-- rescue, item classify/correct, fine-category classify) record the receipt owner.
ALTER TABLE ai_usage_log
    ADD COLUMN user_id UUID REFERENCES users (id) ON DELETE SET NULL;

CREATE INDEX idx_ai_usage_log_user_id ON ai_usage_log (user_id) WHERE user_id IS NOT NULL;
