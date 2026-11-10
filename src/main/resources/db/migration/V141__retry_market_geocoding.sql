-- The geocoder used to send the raw SEFAZ address ("RUA X , 34 , , CENTRO , CIDADE , UF") to Nominatim,
-- which misses it; ~half the markets exhausted their 3 attempts with no coordinates and no UF.
-- The geocoder now cleans the address and falls back to the CNPJ registry + CEP, so give them a fresh try.
UPDATE market_locations
SET geocode_attempts = 0
WHERE latitude IS NULL;
