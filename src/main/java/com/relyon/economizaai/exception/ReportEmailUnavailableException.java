package com.relyon.economizaai.exception;

/** Report e-mail delivery requested but SMTP isn't configured or the send failed. */
public class ReportEmailUnavailableException extends DomainException {

    public ReportEmailUnavailableException() {
        super("report.email.unavailable");
    }
}
