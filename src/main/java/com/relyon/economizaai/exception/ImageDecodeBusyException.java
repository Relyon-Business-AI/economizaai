package com.relyon.economizaai.exception;

/** Too many photos being decoded at once — the caller should retry shortly (503). */
public class ImageDecodeBusyException extends DomainException {

    public ImageDecodeBusyException() {
        super("image.decode.busy");
    }
}
