-- Evidence cap v2: the experimental-UF block now requires failures spread over
-- >= 5 distinct (user, day) pairs across >= 2 users AND >= 2 days, so one user
-- retrying all day (or a single-day portal outage) can never lock a state.
-- The EXHAUSTED evidence rows therefore need to know WHICH user hit the wall.
-- Nullable: pre-existing rows (and admin/test attempts) have no user.
ALTER TABLE state_ingestion_attempts
    ADD COLUMN user_id UUID;
