package com.relyon.economizaai.exception;

public class InvalidDashboardPeriodException extends DomainException {

    public InvalidDashboardPeriodException() {
        super("admin.dashboard.period.invalid");
    }
}
