package com.relyon.economizaai.exception;

/**
 * The scanned NFC-e comes from a merchant the product doesn't support
 * (food service — restaurantes, bares — or an admin-blocked CNPJ). Extends
 * ReceiptParseException so the submit path returns a localized 400 and the
 * async path lands in the same FAILED_PARSE handling with its own key.
 */
public class UnsupportedMerchantException extends ReceiptParseException {

    public UnsupportedMerchantException() {
        // Explicit empty varargs — the single-String overload is the "reason under
        // receipt.parse.failed" constructor, not the message-key one.
        super("receipt.merchant.unsupported", new String[0]);
    }
}
