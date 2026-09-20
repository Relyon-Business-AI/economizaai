-- "Not mine" flag for shared purchases: excludes the line from the household's
-- PERSONAL spend / consumption / savings / reports, but — unlike `excluded` — it
-- STILL feeds the collaborative price index and canonicalization (the price was
-- really paid at that store, whoever it belonged to). Distinct from `excluded`,
-- which drops the line from everything (junk / non-product lines).
ALTER TABLE receipt_items
    ADD COLUMN excluded_from_personal BOOLEAN NOT NULL DEFAULT FALSE;
