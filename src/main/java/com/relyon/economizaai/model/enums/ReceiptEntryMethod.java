package com.relyon.economizaai.model.enums;

/**
 * HOW the user physically entered a receipt — a finer breakdown than {@link ReceiptOrigin}
 * (which drives pipeline behavior and must not be split). Analytics only.
 *
 * <p>Derived server-side from the submitted payload shape when the app doesn't report it: a
 * QR scan arrives as a URL, a typed/photographed chave arrives as a bare 44-digit string. The
 * typed-vs-OCR distinction is INVISIBLE server-side (both are a bare chave), so a bare chave
 * defaults to {@link #MANUAL_CHAVE}; the app may send the method explicitly to split
 * {@link #MANUAL_CHAVE} from {@link #PHOTO_CHAVE}.
 */
public enum ReceiptEntryMethod {
    QR_SCAN,        // scanned the NFC-e QR code (payload is a URL)
    MANUAL_CHAVE,   // typed the 44-digit chave (bare payload) — also the default for any bare chave
    PHOTO_CHAVE,    // photographed the printed chave and OCR'd it (app-reported only)
    PHOTO_RECEIPT,  // photographed the whole receipt, vision-extracted (origin = PHOTO)
    IMPORT,         // bulk import by chave / XML / CSV (origin = IMPORT)
    UNKNOWN;

    /**
     * Best-effort method from the pipeline origin + whether the submitted payload was a bare
     * chave (vs a QR URL). Used when the client didn't report an explicit method.
     */
    public static ReceiptEntryMethod derive(ReceiptOrigin origin, boolean bareChave) {
        if (origin == null) {
            return UNKNOWN;
        }
        return switch (origin) {
            case IMPORT -> IMPORT;
            case PHOTO -> PHOTO_RECEIPT;
            case SCAN -> bareChave ? MANUAL_CHAVE : QR_SCAN;
        };
    }
}
