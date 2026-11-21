package com.relyon.economizaai.exception;

/** Raised when a value typed in the admin does not fit the setting (wrong type, out of range, unknown option). */
public class InvalidSettingValueException extends DomainException {
    public InvalidSettingValueException(String messageKey, String... arguments) {
        super(messageKey, arguments);
    }
}
