package com.relyon.economizaai.exception;

/**
 * Infosimples answered properly but has NOTHING for this chave — a deterministic
 * per-nota outcome (612 "não retornou dados", 607 "parâmetro inválido", 602 wrong
 * service, or an empty data array), NOT a provider outage. Callers must record it
 * in the paid-API ledger WITHOUT counting it toward the circuit breaker: a batch
 * of dead notas must never trip the breaker and starve the recoverable ones.
 */
public class InfosimplesNoDataException extends ReceiptParseException {

    /** The provider result code (602/607/612), or 0 when none applies (empty data, local skip). */
    private final int code;

    public InfosimplesNoDataException(String reason) {
        this(reason, 0);
    }

    public InfosimplesNoDataException(String reason, int code) {
        super(reason);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
