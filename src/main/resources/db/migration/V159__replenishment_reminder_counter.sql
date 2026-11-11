-- Replenishment reminders stop after a few pushes without a new purchase.
-- reminder_count = reminders already sent for the purchase dated reminder_purchase_date;
-- a newer purchase (scanned or logged with "Ja comprei") resets it.
ALTER TABLE notification_rules ADD COLUMN reminder_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notification_rules ADD COLUMN reminder_purchase_date DATE;
