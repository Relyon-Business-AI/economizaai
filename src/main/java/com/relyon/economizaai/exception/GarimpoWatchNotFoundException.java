package com.relyon.economizaai.exception;

public class GarimpoWatchNotFoundException extends DomainException {

    public GarimpoWatchNotFoundException(String watchId) {
        super("garimpo.watch.not.found", watchId);
    }
}
