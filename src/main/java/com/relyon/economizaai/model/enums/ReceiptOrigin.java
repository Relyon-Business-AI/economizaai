package com.relyon.economizaai.model.enums;

/**
 * How a receipt entered the system. SCAN receipts are fetched from SEFAZ and
 * carry a real chave; PHOTO receipts are vision-extracted from a photograph —
 * unverifiable (no SEFAZ authenticity), so they serve the household's personal
 * history only and never feed the collaborative price index. IMPORT receipts
 * enter via bulk import-by-chave (the onboarding path); they reconsult on SEFAZ
 * just like SCAN, but the origin lets the import screen rehydrate its staging
 * list — every non-confirmed import receipt stays visible there until the user
 * confirms (definitive), deletes, or clears it.
 */
public enum ReceiptOrigin {
    SCAN,
    PHOTO,
    IMPORT
}
