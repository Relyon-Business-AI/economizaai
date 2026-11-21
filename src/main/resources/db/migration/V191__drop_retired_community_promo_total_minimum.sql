-- The "total minimum of prices" guard for community promos was retired: the minimums of recent and
-- older prices already cover it. Drops a value saved in the admin for a setting that no longer exists.

DELETE FROM runtime_setting_overrides
WHERE setting_key = 'economizaai.collaborative.min-observations-for-community-promo';
