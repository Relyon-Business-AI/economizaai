-- Location backfill for the collaborative price index (safe, additive — ONLY fills NULLs).
--
-- Closes the gap where ~half of price_observations have NULL city/state because they were
-- written BEFORE their market was geocoded (city/state are snapshotted at write time and
-- never back-filled when a market geocodes later).
--
-- NOTE: re-arming failed-geocode markets is handled by V141__retry_market_geocoding.sql
-- (resets geocode_attempts for latitude-null markets); the geocoder then re-fetches and
-- MarketLocationService.propagateLocationToObservations fills city/state onto their
-- observations going forward. This migration backfills what's already geocodable NOW.
--
-- Idempotent-ish: every UPDATE only touches rows where the target column IS NULL, so
-- re-running it is a no-op once filled and it NEVER overwrites an existing value.

-- (1a) city/state/ibge from the matching market_locations row (join by CNPJ).
--      market_locations.cnpj == price_observations.market_cnpj.
UPDATE price_observations po
SET city = ml.city,
    state = ml.state,
    ibge_city_code = COALESCE(po.ibge_city_code, ml.ibge_city_code)
FROM market_locations ml
WHERE ml.cnpj = po.market_cnpj
  AND po.city IS NULL
  AND ml.city IS NOT NULL;

-- Fill state alone where the market has state but no city (so the city UPDATE above skipped it).
UPDATE price_observations po
SET state = ml.state
FROM market_locations ml
WHERE ml.cnpj = po.market_cnpj
  AND po.state IS NULL
  AND ml.state IS NOT NULL;

-- (1b) state from the source receipt's UF, for observations still missing it.
--      observation -> price_observation_audits.observation_id -> receipts.uf.
--      receipts.uf is NOT NULL (a 2-letter code), so this fills most remaining NULLs.
UPDATE price_observations po
SET state = r.uf
FROM price_observation_audits poa
JOIN receipts r ON r.id = poa.receipt_id
WHERE poa.observation_id = po.id
  AND po.state IS NULL
  AND r.uf IS NOT NULL
  AND r.uf <> '';
