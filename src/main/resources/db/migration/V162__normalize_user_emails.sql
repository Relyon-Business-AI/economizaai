-- Account e-mails become case-insensitive: stored trimmed + lower-cased, and unique
-- on lower(email) so "Maria@x.com" and "maria@x.com" can never be two accounts.
--
-- Defensive: if two rows would collapse into the same normalized address, STOP.
-- Merging or dropping accounts is an owner decision, never a side effect of a
-- migration — the exception aborts the whole migration (nothing is changed).
DO $$
DECLARE
    duplicated_addresses BIGINT;
BEGIN
    SELECT count(*) INTO duplicated_addresses
    FROM (
        SELECT lower(btrim(email))
        FROM users
        GROUP BY lower(btrim(email))
        HAVING count(*) > 1
    ) AS collisions;

    IF duplicated_addresses > 0 THEN
        RAISE EXCEPTION
            'V162 aborted: % e-mail address(es) exist more than once when compared case-insensitively. Resolve the duplicate accounts manually, then re-run.',
            duplicated_addresses;
    END IF;
END $$;

UPDATE users
SET email = lower(btrim(email))
WHERE email <> lower(btrim(email));

-- The original UNIQUE (email) constraint stays; this one closes the casing gap.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email_lower ON users (lower(email));
