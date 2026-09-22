-- Opt-in flag: does the user agree to appear in the PUBLIC "caçador de descontos"
-- leaderboard? Default false — non-opted users are counted only in the admin view.
ALTER TABLE users ADD COLUMN share_in_leaderboard BOOLEAN NOT NULL DEFAULT false;
