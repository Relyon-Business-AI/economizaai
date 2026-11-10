-- Cleanup of city-centroid pollution.
--
-- When Nominatim couldn't resolve a precise street address it returned the CITY CENTROID,
-- and the geocoder stored it as if it were precise. The signature of that bug is several
-- DISTINCT markets sharing the EXACT same (latitude, longitude) — the city's centre point —
-- so they all show up at ~0 km / an identical "perto de você" distance.
--
-- This NULLs latitude/longitude ONLY on rows whose exact coordinate is shared by MORE THAN
-- ONE distinct market (a collision = the centroid signature). Uniquely-located markets are
-- never touched. city/state stay (still correct — they place the store in its UF scope).
-- geocode_failed_at/geocode_attempts are reset so the scheduled job re-attempts them; the
-- new precision check (NominatimGeocoder) now keeps them NULL if the match is still a centroid.

UPDATE market_locations
SET latitude = NULL,
    longitude = NULL,
    geocode_failed_at = NULL,
    geocode_attempts = 0
WHERE (latitude, longitude) IN (
    SELECT latitude, longitude
    FROM market_locations
    WHERE latitude IS NOT NULL AND longitude IS NOT NULL
    GROUP BY latitude, longitude
    HAVING COUNT(*) > 1
);
