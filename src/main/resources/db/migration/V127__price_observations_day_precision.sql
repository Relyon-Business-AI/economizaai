-- Price observations are stored at DAY precision from now on (PriceIndexService#observationDay):
-- a minute-exact observed_at let a reader re-group one store's observations back into a single
-- household's receipt, which is the actual re-identification risk once grocery aggregates relax
-- to n=1. Backfill existing rows the same way. The app writes LocalDateTime wall-clock values
-- through a UTC JVM, so the stored instant's UTC wall-clock IS the receipt's wall-clock date —
-- truncate in UTC to keep the same calendar day the app would have written.
UPDATE price_observations
SET observed_at = (date_trunc('day', observed_at AT TIME ZONE 'UTC')) AT TIME ZONE 'UTC'
WHERE observed_at <> (date_trunc('day', observed_at AT TIME ZONE 'UTC')) AT TIME ZONE 'UTC';
