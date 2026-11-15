-- The home location ("perto de voce") is now one point per household. Every member of a
-- household gets the point that was saved most recently by any of them.
UPDATE users member
SET home_latitude = most_recent.home_latitude,
    home_longitude = most_recent.home_longitude,
    home_set_at = most_recent.home_set_at
FROM (
    SELECT DISTINCT ON (household_id) household_id, home_latitude, home_longitude, home_set_at
    FROM users
    WHERE home_latitude IS NOT NULL
      AND home_longitude IS NOT NULL
    ORDER BY household_id, home_set_at DESC NULLS LAST
) most_recent
WHERE member.household_id = most_recent.household_id
  AND (member.home_latitude IS DISTINCT FROM most_recent.home_latitude
       OR member.home_longitude IS DISTINCT FROM most_recent.home_longitude);
