-- Remembers how the paid Infosimples lookup answered in the server attempt that handed the nota
-- to the device (NO_DATA / FAILED; NULL = not tried), so the device-fetch steps do not pay for
-- the same lookup again (auditoria, item 9).
ALTER TABLE receipts ADD COLUMN infosimples_outcome VARCHAR(20);
