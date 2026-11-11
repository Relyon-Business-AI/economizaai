-- Marks the receipt COPIES a household split creates, so they do not count toward the
-- FREE monthly receipt cap (only receipts the user actually submitted do).
ALTER TABLE receipts ADD COLUMN split_copy BOOLEAN NOT NULL DEFAULT FALSE;
