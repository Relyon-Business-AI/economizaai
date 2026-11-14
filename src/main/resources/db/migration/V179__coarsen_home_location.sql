-- The home point is now kept at 3 decimal places (about 110 m): enough for distances and the
-- nearby radius, without pinpointing a house. Coarsen what was stored at full GPS precision.
UPDATE users
SET home_latitude = ROUND(home_latitude, 3),
    home_longitude = ROUND(home_longitude, 3)
WHERE home_latitude IS NOT NULL
   OR home_longitude IS NOT NULL;
