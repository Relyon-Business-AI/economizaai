-- HOW each receipt was entered (QR scan vs typed/photographed chave vs photo vs import) — a
-- finer analytics cut than `origin`. Backfilled from the existing payload shape: a QR scan was
-- stored as a URL/pipe payload, a typed-or-OCR'd chave as a bare 44-digit string. The typed-vs-OCR
-- split is invisible server-side (both bare), so a bare chave backfills to MANUAL_CHAVE; the app
-- can report PHOTO_CHAVE explicitly going forward.
ALTER TABLE receipts ADD COLUMN entry_method VARCHAR(20);

UPDATE receipts SET entry_method = CASE
    WHEN origin = 'IMPORT' THEN 'IMPORT'
    WHEN origin = 'PHOTO'  THEN 'PHOTO_RECEIPT'
    WHEN origin = 'SCAN' AND btrim(qr_payload) ~ '^[0-9]{44}$' THEN 'MANUAL_CHAVE'
    WHEN origin = 'SCAN' THEN 'QR_SCAN'
    ELSE 'UNKNOWN'
END;

CREATE INDEX idx_receipts_entry_method ON receipts (entry_method);
