-- The app blocks duplicate custom-category names case-insensitively (CustomCategoryService),
-- but the table's UNIQUE(household_id, name) is case-sensitive, so the two disagree on what
-- "same name" means. Enforce the app's rule at the DB with a functional unique index on the
-- lowercased name. The old case-sensitive UNIQUE is subsumed by this (kept; harmless).
-- Pre-existing case-variant duplicates are not expected (the old create path upserted by
-- name-ignore-case), so this index creates cleanly.
CREATE UNIQUE INDEX IF NOT EXISTS ux_household_custom_categories_household_lower_name
    ON household_custom_categories (household_id, lower(name));
