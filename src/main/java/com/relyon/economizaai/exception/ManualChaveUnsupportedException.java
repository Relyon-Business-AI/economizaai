package com.relyon.economizaai.exception;

/**
 * Raised when a user submits a bare 44-digit chave (manual entry, damaged QR)
 * for a state whose NFC-e cannot be retrieved without the QR's signature AND has
 * no by-chave fallback — currently only RS, whose SEFAZ requires a gov.br login
 * or digital certificate to consult by chave. The user must scan the QR instead.
 */
public class ManualChaveUnsupportedException extends DomainException {

    public ManualChaveUnsupportedException(String state) {
        super("receipt.manual-chave.unsupported", state);
    }
}
