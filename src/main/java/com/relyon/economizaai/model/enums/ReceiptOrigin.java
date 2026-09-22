package com.relyon.economizaai.model.enums;

/**
 * How a receipt entered the system. SCAN receipts are fetched from SEFAZ and
 * carry a real chave; PHOTO receipts are vision-extracted from a photograph —
 * unverifiable (no SEFAZ authenticity), so they serve the household's personal
 * history only and never feed the collaborative price index.
 */
public enum ReceiptOrigin {
    SCAN,
    PHOTO
}
