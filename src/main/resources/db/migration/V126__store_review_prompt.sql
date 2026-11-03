-- In-app store rating prompt (SKStoreReviewController / Play In-App Review): the server
-- decides WHEN the app may ask (positive moment = Nth confirmed nota, no recent failure)
-- and records every prompt so the cap/cooldown holds across devices and reinstalls.
ALTER TABLE users
    ADD COLUMN store_review_prompt_count   INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN store_review_last_prompted_at TIMESTAMP WITH TIME ZONE;
