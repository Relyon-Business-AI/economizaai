package com.relyon.economizaai.exception;

/**
 * Infosimples answered properly but has NOTHING for this chave — a deterministic
 * per-nota outcome (612 "não retornou dados", 607 "parâmetro inválido", 602 wrong
 * service, or an empty data array), NOT a provider outage. Callers must record it
 * in the paid-API ledger WITHOUT counting it toward the circuit breaker: a batch
 * of dead notas must never trip the breaker and starve the recoverable ones.
 */
public class InfosimplesNoDataException extends ReceiptParseException {
    public InfosimplesNoDataException(String reason) {
        super(reason);
    }
}
