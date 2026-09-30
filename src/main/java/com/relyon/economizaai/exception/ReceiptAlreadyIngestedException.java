package com.relyon.economizaai.exception;

import com.relyon.economizaai.service.privacy.LogMasker;

public class ReceiptAlreadyIngestedException extends DomainException {

    public ReceiptAlreadyIngestedException(String chaveAcesso) {
        // Masked at the source: the argument lands in the user message AND in the
        // handler's log line — the full 44-digit chave must never reach either.
        super("receipt.already.ingested", LogMasker.chave(chaveAcesso));
    }
}
