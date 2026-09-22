package com.relyon.economizaai.exception;

/**
 * The SEFAZ consult portal answered with a rejection page (a numbered cStat
 * error) instead of the DANFE. When the chave says the note was issued in
 * contingency, the honest story for the user is "not available at SEFAZ yet,
 * try later" — the emitter has up to 24h to transmit it; otherwise a generic
 * "SEFAZ refused this QR" with the code.
 */
public class SefazPortalRejectionException extends ReceiptParseException {

    public SefazPortalRejectionException(String rejectionCode, boolean contingencyEmission) {
        super(contingencyEmission ? "receipt.contingency.pending" : "receipt.sefaz.rejected_qr",
                rejectionCode);
    }
}
