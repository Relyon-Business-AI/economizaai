package com.relyon.economizaai.exception;

/**
 * Raised at SUBMIT when a manually-typed bare chave fails the mod-11 check digit —
 * a typo, decidable synchronously and for free. Without this guard the chave enters
 * the async pipeline, spends a paid Infosimples call (code 607, deterministic) and
 * fires ops alerts, só pra falhar com FAILED_PARSE do mesmo jeito.
 */
public class InvalidChaveException extends DomainException {

    public InvalidChaveException() {
        super("receipt.chave.typo");
    }
}
