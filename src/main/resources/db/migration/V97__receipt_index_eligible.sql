-- Index-eligibility marker for the generic device-fetch fallback.
--
-- When a receipt is fetched on-device (client-authored content), the only tie to
-- SEFAZ is the embedded chave — forgeable in principle. Server-fetched content is
-- self-verified by our fetch. So device-fetched content from a NON-trusted state
-- (one that isn't on the device-fetch trust allowlist) must stay personal-history
-- only and never feed the collaborative price index — same trust class as a
-- PHOTO-origin receipt. This flag carries that decision, made at ingest time, to
-- the confirm-time index gate (PriceIndexService#isContributionBlocked).
--
-- Default TRUE: every existing receipt and every normal (server-fetched, or
-- trusted device-fetch like PE) receipt is index-eligible. Only untrusted
-- device-fetch content is marked FALSE.
ALTER TABLE receipts
    ADD COLUMN index_eligible BOOLEAN NOT NULL DEFAULT TRUE;
