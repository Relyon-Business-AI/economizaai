-- The spending report (the WEEKLY_REPORT notice) can cover the week, the month or both — the
-- two are independent switches. Everyone starts where they are today: weekly on, monthly off.
ALTER TABLE users ADD COLUMN spending_report_weekly BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN spending_report_monthly BOOLEAN NOT NULL DEFAULT FALSE;
